using PhoneBridge.Core;

namespace PhoneBridge.Core.Tests;

public class ReleaseTests
{
    [Theory]
    [InlineData("0.24", "0.23", true)]
    [InlineData("0.23.1", "0.23", true)]
    [InlineData("1.0", "0.99", true)]
    [InlineData("0.23", "0.23", false)]
    [InlineData("0.23", "0.23.0", false)]
    [InlineData("0.22", "0.23", false)]
    public void VersionsCompareLikeTheMac(string a, string b, bool newer) => Assert.Equal(newer, Releases.IsNewer(a, b));

    /// <summary>Shaped like GitHub's answer for this repo's v0.23 release (asset digests included).</summary>
    [Fact]
    public void FindsTheWindowsAssetAndItsChecksum()
    {
        const string json = """
            {"tag_name":"v0.24","body":"What's new","assets":[
              {"name":"PhoneBridge.apk","browser_download_url":"https://example/apk","digest":"sha256:1ec1"},
              {"name":"PhoneBridge-Windows.exe","browser_download_url":"https://example/exe","digest":"sha256:ABCDEF"}]}
            """;
        var r = Releases.Parse(json, "PhoneBridge-Windows.exe");
        Assert.Equal(new Releases.Release("0.24", "https://example/exe", "ABCDEF", "What's new"), r);
        Assert.Null(Releases.Parse(json.Replace("PhoneBridge-Windows.exe", "x.exe"), "PhoneBridge-Windows.exe"));
        Assert.Null(Releases.Parse("not json", "PhoneBridge-Windows.exe"));
    }
}
