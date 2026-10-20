#!/usr/bin/env python3
"""Audit the curated Commons catalogue; --probe performs real, bounded network verification."""
import argparse
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import io
import json
from pathlib import Path
import re
import os
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from urllib.parse import urlencode, urlsplit

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "app/src/main/assets/cityscapes.json"
REPORT = ROOT / "docs/city-source-verification.json"
SHEET = ROOT / "docs/city-source-verification.contact-sheet.jpg"
CITIES = ["Tokyo", "New York", "Hong Kong", "Singapore", "London", "Chicago", "Seoul"]
MAX_FILE = 16 * 1024 * 1024
MAX_METADATA = 512 * 1024
UA = "AmbientFlowTV/1.0 (Commons cityscape verification; documented Action API)"
FIELDS = "Artist|Credit|Attribution|LicenseShortName|LicenseUrl|License|AttributionRequired|Restrictions|NonFree|Copyrighted|UsageTerms|ImageDescription|Categories"


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Plain(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.feed(str(text))

    def handle_data(self, data):
        self.parts.append(data)


def plain(text):
    return " ".join("".join(Plain(text).parts).replace("\xa0", " ").split())


def qualifies(width, height):
    return (type(width) is int and type(height) is int
            and 3840 <= width <= 7680 and 2160 <= height <= 5120 and width > height)


def canonical_license(name, code, url, attribution, restrictions, nonfree="", copyrighted=""):
    if attribution.lower() != "false" or restrictions != "" or nonfree.lower() not in ("", "false"):
        return None
    expected = None
    if name == "CC0" and code == "cc0":
        expected = "zero"
    elif name == "Public domain" and code == "pd" and copyrighted.lower() == "false":
        expected = "mark"
        if not url:
            # Commons PD-self records often have no LicenseUrl. This is a status link,
            # not a claim that the source file carried a PDM instrument.
            return "https://creativecommons.org/publicdomain/mark/1.0/"
    if not expected:
        return None
    try:
        uri = urlsplit(url)
        path = rf"/publicdomain/{expected}/1\.0(?:/deed(?:\.[a-zA-Z_-]+)?)?/?"
        if (uri.scheme not in ("http", "https") or uri.netloc != "creativecommons.org"
                or "?" in url or "#" in url or not re.fullmatch(path, uri.path)):
            return None
        return f"https://creativecommons.org/publicdomain/{expected}/1.0/"
    except ValueError:
        return None


def safe_url(value, kind):
    try:
        uri = urlsplit(value)
        host, prefix = (("commons.wikimedia.org", "/wiki/File:") if kind == "source"
                        else (uri.netloc if uri.netloc in ("upload.wikimedia.org", "thumb.wikimedia.org") else "upload.wikimedia.org", "/wikipedia/commons/"))
        return (uri.scheme == "https" and uri.netloc == host and uri.path.startswith(prefix)
                and not uri.fragment)
    except ValueError:
        return False


def candidate(seed, page):
    require(str(page.get("pageid")) == seed["id"] and page.get("ns") == 6, "Wrong Commons page")
    require(page.get("imagerepository") == "local", "Not a Commons-hosted file")
    require(page.get("title", "").startswith("File:"), "Not a file page")
    info = page.get("imageinfo", [{}])[0]
    require(info.get("mime") == "image/jpeg", "Not a JPEG")
    require(qualifies(info.get("width"), info.get("height")), "Original not bounded native landscape 4K")
    require(type(info.get("size")) is int and 0 < info["size"] <= MAX_FILE, "Original size exceeds budget")
    meta = info.get("extmetadata", {})
    field = lambda key: plain(meta.get(key, {}).get("value", ""))
    require("Restrictions" in meta, "Missing explicit Restrictions metadata")
    license_url = canonical_license(field("LicenseShortName"), field("License"), field("LicenseUrl"),
                                    field("AttributionRequired"), field("Restrictions"),
                                    field("NonFree"), field("Copyrighted"))
    require(license_url, "Not explicit unrestricted CC0/public domain")
    require(field("Artist"), "No creator metadata")
    require(qualifies(info.get("thumbwidth"), info.get("thumbheight")), "Standard thumbnail cannot supply 4K crop")
    require(info["thumbwidth"] <= info["width"] and info["thumbheight"] <= info["height"], "Upscaled thumbnail metadata")
    require(safe_url(info.get("thumburl", ""), "image") and safe_url(info.get("descriptionurl", ""), "source"), "Unsafe source URL")
    return info, {key: plain(value.get("value", "")) for key, value in meta.items()}, license_url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError(f"Redirect refused ({code}): {newurl}")


def get(url, limit):
    uri = urlsplit(url)
    require(uri.scheme == "https" and uri.netloc in ("commons.wikimedia.org", "upload.wikimedia.org", "thumb.wikimedia.org"), "Unapproved network host")
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=40) as response:
        declared = int(response.headers.get("Content-Length", "-1"))
        require(declared <= limit, "Oversized response")
        data = response.read(limit + 1)
        require(len(data) <= limit, "Response exceeded byte cap")
        return data, response.headers.get_content_type()


def metadata_url(seeds):
    return "https://commons.wikimedia.org/w/api.php?" + urlencode(dict(
        action="query", format="json", pageids="|".join(seed["id"] for seed in seeds),
        prop="imageinfo", iiprop="url|size|mime|extmetadata", iiurlwidth=3840, iiextmetadatafilter=FIELDS,
        iiextmetadatalanguage="en", maxlag=5))


def read_asset():
    asset = json.loads(ASSET.read_text())
    require(asset.get("version") == 2 and asset.get("mode") == "automatic-curated-commons", "Wrong catalogue mode")
    require(asset.get("cities") == CITIES, "City list mismatch")
    photos = asset.get("photos", [])
    require(7 <= len(photos) <= 28, "Unexpected catalogue size")
    ids = set()
    for seed in photos:
        require(re.fullmatch(r"[1-9][0-9]{0,19}", seed["id"]) and seed["id"] not in ids, "Invalid/duplicate ID")
        require(seed["city"] in CITIES and seed["title"], "Invalid seed city/title")
        ids.add(seed["id"])
    require({seed["city"] for seed in photos} == set(CITIES), "Missing city coverage")
    return photos


def probe(seeds):
    from PIL import Image, ImageDraw, ImageOps
    checked = datetime.now(timezone.utc).isoformat()
    report = dict(schemaVersion=2, provider="Wikimedia Commons", checkedAt=checked,
                  mode="automatic-curated-commons", runtimeNetworking=True,
                  sources=["https://www.mediawiki.org/wiki/API:Imageinfo",
                           "https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia",
                           "https://creativecommons.org/publicdomain/zero/1.0/",
                           "https://creativecommons.org/publicdomain/mark/1.0/"],
                  method="Live documented API metadata + standard 3840px API-returned JPEG rendition + full Pillow decode + center crop without upscaling. No manually constructed thumbnail URL.",
                  archiveReference="Read CityRepository.kt directly from dist/AmbientFlow-source.zip without extraction/overwrite.",
                  photos=[], failures=[], metadataRequests=[],
                  caveats=["Metadata is Commons' assertion, not a legal warranty or worldwide property/model release.",
                           "PD records without LicenseUrl use the PDM status link for details; not a claim that the source explicitly applied PDM.",
                           "Pillow crop probes are not Android BitmapRegionDecoder/device lifecycle tests.",
                           "Native 4K means measured source pixels sufficient for a 4K crop, not independently proven camera/sensor provenance."])
    if REPORT.exists():
        previous = json.loads(REPORT.read_text())
        if previous.get("failures"):
            report["previousProbe"] = {key: previous.get(key) for key in ("checkedAt", "method", "verifiedCount", "failures")}
        elif "previousProbe" in previous:
            report["previousProbe"] = previous["previousProbe"]
        report["earlierProbeHistory"] = previous.get("earlierProbeHistory", [])
        if "previousProbe" in previous and previous.get("failures"):
            report["earlierProbeHistory"].append(previous["previousProbe"])
    previews = []
    for offset in range(0, len(seeds), 4):
        batch = seeds[offset:offset + 4]
        url = metadata_url(batch)
        report["metadataRequests"].append(url)
        try:
            raw, _ = get(url, MAX_METADATA)
            response = json.loads(raw)
            require("error" not in response, f"API error: {response.get('error')}")
            pages = response.get("query", {}).get("pages", {})
        except Exception as error:
            report["failures"].append(dict(ids=[s["id"] for s in batch], stage="metadata", error=str(error)))
            continue
        for seed in batch:
            try:
                page = pages.get(seed["id"], {})
                info, fields, license_url = candidate(seed, page)
                data, mime = get(info["thumburl"], MAX_FILE)
                require(mime == "image/jpeg", f"Unexpected content type: {mime}")

                with Image.open(io.BytesIO(data)) as original:
                    require(original.format == "JPEG", "Not delivered as JPEG")
                    require(qualifies(*original.size), "Delivered JPEG cannot supply native 4K")
                    require(original.width <= info["width"] and original.height <= info["height"], "Delivered image exceeds original dimensions")
                    original.load()
                    width, height = original.size
                    crop_width = min(width, height * 3840 // 2160)
                    crop_height = min(height, width * 2160 // 3840)
                    require(crop_width >= 3840 and crop_height >= 2160, "Upscaling would be required")
                    left, top = (width - crop_width) // 2, (height - crop_height) // 2
                    crop = original.crop((left, top, left + crop_width, top + crop_height))
                    output = crop.resize((3840, 2160), Image.Resampling.LANCZOS)
                    encoded = io.BytesIO()
                    output.save(encoded, "JPEG", quality=90)
                    with Image.open(io.BytesIO(encoded.getvalue())) as result:
                        result.load()
                        require(result.size == (3840, 2160), "Crop output not 4K")
                    previews.append((seed, ImageOps.contain(output, (480, 270))))
                    crop.close()
                    output.close()
                report["photos"].append(dict(id=seed["id"], city=seed["city"], title=page["title"],
                    creator=fields["Artist"], license=fields["LicenseShortName"], licenseUrl=license_url,
                    sourceUrl=info["descriptionurl"], downloadUrl=info["thumburl"], originalUrl=info["url"],
                    sourceDimensions=[info["width"], info["height"]], deliveredDimensions=[width, height],
                    deliveredBytes=len(data), deliveredMime=mime,
                    deliveredSha256=hashlib.sha256(data).hexdigest(),
                    cropBox=[left, top, left + crop_width, top + crop_height],
                    outputDimensions=[3840, 2160], outputJpegProbeBytes=len(encoded.getvalue()),
                    extmetadata=info["extmetadata"], normalizedMetadata=fields,
                    status="network-verified", visualReview="pending"))
                print(f"PASS {seed['city']} {seed['id']}: {width}x{height}, {len(data)} bytes, {fields['LicenseShortName']}, crop 3840x2160", flush=True)
            except Exception as error:
                report["failures"].append(dict(id=seed["id"], city=seed["city"], stage="photo", error=str(error)))
                print(f"FAIL {seed['city']} {seed['id']}: {error}", flush=True)
            time.sleep(0.5)
        time.sleep(1)
    rows = (len(previews) + 1) // 2
    if rows:
        sheet = Image.new("RGB", (1000, rows * 310), (24, 24, 24))
        draw = ImageDraw.Draw(sheet)
        for index, (seed, preview) in enumerate(previews):
            x, y = (index % 2) * 500 + 10, (index // 2) * 310 + 5
            sheet.paste(preview, (x, y))
            draw.text((x, y + 275), f"{seed['city']} | Commons {seed['id']} | 4K center crop", fill="white")
            preview.close()
        sheet.save(SHEET, "JPEG", quality=90)
        sheet.close()
    report["verifiedCount"] = len(report["photos"])
    report["status"] = "network-probed-visual-review-pending" if not report["failures"] else "probe-failures"
    report["contactSheet"] = SHEET.name if rows else None
    REPORT.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    require(not report["failures"], f"Probe failures: {len(report['failures'])}; see report")


def audit(seeds):
    report = json.loads(REPORT.read_text())
    require(report.get("schemaVersion") == 2 and report.get("mode") == "automatic-curated-commons", "Stale verification report")
    require(not report.get("failures") and report.get("verifiedCount") == len(seeds), "Incomplete network evidence")
    records = {entry["id"]: entry for entry in report["photos"]}
    require(len(records) == len(seeds), "Duplicate evidence IDs")
    for seed in seeds:
        entry = records[seed["id"]]
        require(entry["city"] == seed["city"] and entry["status"] == "network-verified", "City/status mismatch")
        require(qualifies(*entry["sourceDimensions"]) and qualifies(*entry["deliveredDimensions"]), "Invalid source/delivery dimensions")
        require(all(d <= s for d, s in zip(entry["deliveredDimensions"], entry["sourceDimensions"])), "Delivered image exceeds original dimensions")
        require(entry["outputDimensions"] == [3840, 2160] and 0 < entry["deliveredBytes"] <= MAX_FILE, "Invalid output/bytes")
        fields = entry["normalizedMetadata"]
        require(canonical_license(fields.get("LicenseShortName", ""), fields.get("License", ""), fields.get("LicenseUrl", ""),
                                  fields.get("AttributionRequired", ""), fields.get("Restrictions", "missing"),
                                  fields.get("NonFree", ""), fields.get("Copyrighted", "")), "Invalid license evidence")
    require(SHEET.is_file(), "Contact sheet missing")
    print(f"PASS: saved real-network evidence for {len(seeds)} photos / {len(CITIES)} cities; checked {report['checkedAt']}.")
    print("Offline audit only; use --probe to re-fetch. Visual review status: " + report["status"])


class PolicyTests(unittest.TestCase):
    def test_dimensions(self):
        for width, height in ((3840, 2160), (6000, 4000), (7680, 5120), (7680, 2160), (3840, 3800)):
            self.assertTrue(qualifies(width, height))
            self.assertGreaterEqual(min(width, height * 3840 // 2160), 3840)
            self.assertGreaterEqual(min(height, width * 2160 // 3840), 2160)
        for size in ((3839, 2160), (3840, 2159), (2160, 3840), (4000, 4000), (7681, 2160), (6000, 5121), (True, 2160)):
            self.assertFalse(qualifies(*size))

    def test_cc0_and_pd(self):
        self.assertTrue(canonical_license("CC0", "cc0", "http://creativecommons.org/publicdomain/zero/1.0/deed.en", "false", ""))
        self.assertTrue(canonical_license("Public domain", "pd", "", "false", "", copyrighted="False"))
        self.assertTrue(canonical_license("Public domain", "pd", "https://creativecommons.org/publicdomain/mark/1.0/", "false", "", copyrighted="False"))

    def test_fail_closed(self):
        base = ["CC0", "cc0", "https://creativecommons.org/publicdomain/zero/1.0/", "false", "", "", "True"]
        for index, value in ((0, "CC BY 4.0"), (0, "CC BY-SA 4.0"), (0, "FAL"), (0, "GFDL"),
                             (1, "cc-by-sa-4.0"), (2, ""), (3, "true"), (3, ""), (4, "personality"),
                             (5, "true"), (2, "https://creativecommons.org.evil.test/publicdomain/zero/1.0/")):
            values = base.copy()
            values[index] = value
            self.assertIsNone(canonical_license(*values), (index, value))
        self.assertIsNone(canonical_license("Public domain", "pd", "", "false", ""))

    def test_hosts(self):
        self.assertTrue(safe_url("https://upload.wikimedia.org/wikipedia/commons/a/ab/City.jpg?utm_source=x", "image"))
        for url in ("http://upload.wikimedia.org/wikipedia/commons/a.jpg", "https://evil.test/wikipedia/commons/a.jpg",

                    "https://user@upload.wikimedia.org/wikipedia/commons/a.jpg"):
            self.assertFalse(safe_url(url, "image"))


def jvm_self_test():
    """Execute the actual compiled Kotlin pure policies, without an Android runtime."""
    classes = ROOT / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
    require((classes / "org/ambientflow/tv/CityRepository.class").is_file(),
            "Compile :app:compileDebugKotlin first (built-in Kotlin debug output required)")
    modules = ROOT / ".gradle-home/caches/modules-2/files-2.1"
    # Android method signatures must resolve, but no Android method is called by these policies.
    sdk_line = next(line for line in (ROOT / "local.properties").read_text().splitlines() if line.startswith("sdk.dir="))
    android_jars = sorted((Path(sdk_line.split("=", 1)[1]) / "platforms").glob("*/android.jar"))
    require(android_jars, "Android SDK platform jar missing")
    jars = [path for group in ("org.jetbrains.kotlin/kotlin-stdlib", "com.squareup.okhttp3/okhttp", "com.squareup.okio/okio-jvm")
            for path in (modules / group).glob("*/*/*.jar") if not path.name.endswith("-sources.jar")]
    require(jars, "Cached Kotlin/JVM dependencies missing")
    classpath = os.pathsep.join(str(path) for path in [classes, android_jars[-1], *jars])
    source = '''
import org.ambientflow.tv.CityRepository;
class CityPolicyProbe {
    static int count = 0;
    static void check(boolean value) { count++; if (!value) throw new AssertionError("Policy case " + count); }
    static String license(String name, String code, String url, String attr, String restrictions, String nonFree, String copyright) {
        return CityRepository.canonicalLicense(name, code, url, attr, restrictions, nonFree, copyright);
    }
    public static void main(String[] args) {
        for (int w=3700; w<7900; w+=137) for (int h=2000; h<5300; h+=113) {
            boolean result=CityRepository.qualifies(w,h);
            check(result == (w>=3840 && w<=7680 && h>=2160 && h<=5120 && w>h));
            if(result) { check(Math.min(w,(long)h*3840/2160)>=3840); check(Math.min(h,(long)w*2160/3840)>=2160); }
        }
        check(CityRepository.qualifies(3840,2160));
        check(!CityRepository.qualifies(3839,2160));
        check(!CityRepository.qualifies(3840,2159));
        check(!CityRepository.qualifies(7681,5120));
        String u="https://creativecommons.org/publicdomain/zero/1.0/";
        check(license("CC0","cc0",u,"false","","","True")!=null);
        check(license("CC0","cc0","http://creativecommons.org/publicdomain/zero/1.0/deed.en","false","","", "True")!=null);
        check(license("Public domain","pd","","false","","","False")!=null);
        check(license("Public domain","pd","https://creativecommons.org/publicdomain/mark/1.0/","false","","","False")!=null);
        for(String name:new String[]{"CC BY 4.0","CC BY-SA 4.0","FAL","GFDL","CC0 / CC BY","Unknown"})
            check(license(name,"cc0",u,"false","","","True")==null);
        for(String url:new String[]{"", "https://creativecommons.org.evil.test/publicdomain/zero/1.0/", u+"?x=1", u+"#x", "https://user@creativecommons.org/publicdomain/zero/1.0/", "https://creativecommons.org:443/publicdomain/zero/1.0/"})
            check(license("CC0","cc0",url,"false","","","True")==null);
        check(license("CC0","cc0",u,"true","","","True")==null);
        check(license("CC0","cc0",u,"","","","True")==null);
        check(license("CC0","cc0",u,"false","personality","","True")==null);
        check(license("CC0","cc0",u,"false","","true","True")==null);
        check(license("Public domain","pd","","false","","","True")==null);
        System.out.println("PASS: actual compiled Kotlin eligibility policies, " + count + " assertions");
    }
}
'''
    with tempfile.TemporaryDirectory(prefix="city-policy-") as folder:
        path = Path(folder) / "CityPolicyProbe.java"
        path.write_text(source)
        subprocess.run(["java", "--class-path", classpath, str(path)], check=True, timeout=60)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", action="store_true", help="Fetch live metadata and every standard 3840px rendition; overwrite JSON evidence/contact sheet; requires Pillow")
    parser.add_argument("--self-test", action="store_true", help="Run pure eligibility tests, not claims of network verification")
    parser.add_argument("--jvm-self-test", action="store_true", help="Run the actual compiled Kotlin pure policy on the JVM; needs debug compile, project-local Gradle cache and JDK")
    args = parser.parse_args()
    try:
        seeds = read_asset()
        if args.self_test:
            result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PolicyTests))
            if not result.wasSuccessful():
                return 1
        if args.jvm_self_test:
            jvm_self_test()
        if args.probe:
            probe(seeds)
        audit(seeds)
    except (ValueError, OSError, KeyError, TypeError, ImportError, subprocess.SubprocessError, StopIteration) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
