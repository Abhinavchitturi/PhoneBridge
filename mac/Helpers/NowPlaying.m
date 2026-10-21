// Mac "Now Playing" watcher, loaded into /usr/bin/perl by MacMedia.swift.
// Since macOS 15.4 MediaRemote only answers Apple-signed processes, and the system perl is one of them,
// so the app runs this inside perl instead of calling MediaRemote itself.
//
// stdout: one JSON object per line whenever the playing item changes
//         {"title","artist","album","playing","pid","art"(base64 JPEG, only when the artwork changes)} or {} for nothing.
// stdin:  one command per line: play_pause, play, pause, next, prev. EOF (the app quit) ends the process.
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#include <dlfcn.h>

typedef void (*GetInfoFn)(dispatch_queue_t, void (^)(NSDictionary *));
typedef void (*GetBoolFn)(dispatch_queue_t, void (^)(Boolean));
typedef void (*GetPidFn)(dispatch_queue_t, void (^)(int));
typedef void (*RegisterFn)(dispatch_queue_t);
typedef Boolean (*SendCommandFn)(int, NSDictionary *);

static GetInfoFn getInfo;
static GetBoolFn getIsPlaying;
static GetPidFn getPid;
static SendCommandFn sendCommand;
static NSString *lastLine;
static NSString *lastArtId;

static NSString *artJPEG(NSData *data) {
    CGImageSourceRef src = CGImageSourceCreateWithData((__bridge CFDataRef)data, NULL);
    if (!src) return nil;
    NSDictionary *opts = @{(id)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
                           (id)kCGImageSourceCreateThumbnailWithTransform: @YES,
                           (id)kCGImageSourceThumbnailMaxPixelSize: @200};
    CGImageRef img = CGImageSourceCreateThumbnailAtIndex(src, 0, (__bridge CFDictionaryRef)opts);
    CFRelease(src);
    if (!img) return nil;
    NSMutableData *out = [NSMutableData data];
    CGImageDestinationRef dst = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)out, CFSTR("public.jpeg"), 1, NULL);
    CGImageDestinationAddImage(dst, img, (__bridge CFDictionaryRef)@{(id)kCGImageDestinationLossyCompressionQuality: @0.8});
    BOOL ok = CGImageDestinationFinalize(dst);
    CFRelease(dst);
    CGImageRelease(img);
    return ok ? [out base64EncodedStringWithOptions:0] : nil;
}

static void emit(NSDictionary *info, BOOL playing, int pid) {
    NSString *title = info[@"kMRMediaRemoteNowPlayingInfoTitle"];
    NSMutableDictionary *m = [NSMutableDictionary dictionary];
    if (title.length) {
        m[@"title"] = title;
        m[@"artist"] = info[@"kMRMediaRemoteNowPlayingInfoArtist"] ?: @"";
        m[@"album"] = info[@"kMRMediaRemoteNowPlayingInfoAlbum"] ?: @"";
        m[@"playing"] = @(playing);
        m[@"pid"] = @(pid);
    }
    NSString *line = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:m options:0 error:nil]
                                           encoding:NSUTF8StringEncoding];
    NSData *art = info[@"kMRMediaRemoteNowPlayingInfoArtworkData"];
    NSString *artId = title.length ? [NSString stringWithFormat:@"%@|%@", title,
                                      info[@"kMRMediaRemoteNowPlayingInfoArtworkIdentifier"] ?: @(art.length)] : nil;
    BOOL newArt = art.length && ![artId isEqual:lastArtId];
    if ([line isEqual:lastLine] && !newArt) return;
    lastLine = line;
    if (newArt) {
        NSString *jpeg = artJPEG(art);
        if (jpeg) {
            lastArtId = artId;
            m[@"art"] = jpeg;
            line = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:m options:0 error:nil]
                                         encoding:NSUTF8StringEncoding];
        }
    }
    printf("%s\n", line.UTF8String);
    fflush(stdout);
}

static void refresh(void) {
    dispatch_queue_t q = dispatch_get_main_queue();
    getInfo(q, ^(NSDictionary *info) {
        getIsPlaying(q, ^(Boolean playing) {
            if (getPid) getPid(q, ^(int pid) { emit(info, playing, pid); });
            else emit(info, playing, 0);
        });
    });
}

static void command(NSString *cmd) {
    // MRCommand values: 0 = play, 1 = pause, 2 = toggle play/pause, 4 = next track, 5 = previous track.
    NSDictionary *codes = @{@"play": @0, @"pause": @1, @"play_pause": @2, @"next": @4, @"prev": @5};
    NSNumber *code = codes[cmd];
    if (code && sendCommand) sendCommand(code.intValue, nil);
}

void pb_nowplaying_run(void) {
    void *mr = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY);
    if (!mr) { fprintf(stderr, "MediaRemote missing\n"); exit(1); }
    getInfo = (GetInfoFn)dlsym(mr, "MRMediaRemoteGetNowPlayingInfo");
    getIsPlaying = (GetBoolFn)dlsym(mr, "MRMediaRemoteGetNowPlayingApplicationIsPlaying");
    getPid = (GetPidFn)dlsym(mr, "MRMediaRemoteGetNowPlayingApplicationPID");
    sendCommand = (SendCommandFn)dlsym(mr, "MRMediaRemoteSendCommand");
    RegisterFn registerFor = (RegisterFn)dlsym(mr, "MRMediaRemoteRegisterForNowPlayingNotifications");
    if (!getInfo || !getIsPlaying) { fprintf(stderr, "MediaRemote API missing\n"); exit(1); }

    if (registerFor) registerFor(dispatch_get_main_queue());
    for (NSString *name in @[@"kMRMediaRemoteNowPlayingInfoDidChangeNotification",
                             @"kMRMediaRemoteNowPlayingApplicationIsPlayingDidChangeNotification",
                             @"kMRMediaRemoteNowPlayingApplicationDidChangeNotification"]) {
        [NSNotificationCenter.defaultCenter addObserverForName:name object:nil queue:NSOperationQueue.mainQueue
                                                    usingBlock:^(NSNotification *n) { refresh(); }];
    }
    // Some players don't post every change; a slow poll catches those (output is deduplicated).
    [NSTimer scheduledTimerWithTimeInterval:3 repeats:YES block:^(NSTimer *t) { refresh(); }];
    refresh();

    [NSThread detachNewThreadWithBlock:^{
        char buf[256];
        while (fgets(buf, sizeof buf, stdin)) {
            NSString *cmd = [[NSString stringWithUTF8String:buf]
                             stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
            dispatch_async(dispatch_get_main_queue(), ^{ command(cmd); });
        }
        exit(0); // the app went away
    }];
    CFRunLoopRun();
}
