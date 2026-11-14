import hashlib, html.parser, json, pathlib, re, subprocess, urllib.parse, urllib.request

INDEX = "https://www.jra.go.jp/datafile/seiseki/report/2026.html"
OUT = pathlib.Path("historical-pdf-probe.json")
PDF = pathlib.Path("hanshin-2026-4-5.pdf")
TXT = pathlib.Path("hanshin-2026-4-5.txt")

class Links(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.current = None
        self.links = []
    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self.current = dict(attrs).get("href")
            self.buf = []
    def handle_data(self, data):
        if self.current is not None:
            self.buf.append(data)
    def handle_endtag(self, tag):
        if tag == "a" and self.current is not None:
            self.links.append((self.current, "".join(self.buf).strip()))
            self.current = None
            self.buf = []

def fetch(url):
    req = urllib.request.Request(url, headers={
        "User-Agent":"Mozilla/5.0 JRA-EDGE-HISTORICAL-READONLY/1.0",
        "Accept-Language":"ja",
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read(), r.status, dict(r.headers.items())

raw, status, headers = fetch(INDEX)
text = raw.decode("utf-8", errors="replace")
p = Links(); p.feed(text)
candidates = [(h,t) for h,t in p.links if ("阪神" in t and ("5日" in t or "第5日" in t))]
direct_fallback = "https://www.jra.go.jp/datafile/seiseki/report/2026/2026-4hanshin5.pdf"
if candidates:
    href, label = next(((h,t) for h,t in candidates if "4回" in t and "5日" in t), candidates[-1])
    pdf_url = urllib.parse.urljoin(INDEX, href)
    discovery = "INDEX_LINK"
else:
    label = "4回阪神5日（命名規則から直接検証）"
    pdf_url = direct_fallback
    discovery = "DIRECT_PATTERN_VALIDATED_BY_HTTP_AND_PDF_MAGIC"

pdf_raw, pdf_status, pdf_headers = fetch(pdf_url)
if not pdf_raw.startswith(b"%PDF-"):
    raise SystemExit("NOT_A_PDF")
PDF.write_bytes(pdf_raw)

tool = subprocess.run(["bash","-lc","command -v pdftotext"],text=True,capture_output=True)
has_pdftotext = tool.returncode == 0
extract_ok = False
if has_pdftotext:
    cp = subprocess.run(["pdftotext","-layout",str(PDF),str(TXT)],text=True,capture_output=True)
    extract_ok = cp.returncode == 0 and TXT.exists()
else:
    cp = None

excerpt = ""
if extract_ok:
    body = TXT.read_text(encoding="utf-8",errors="replace")
    m = re.search(r"阪神ジャンプ.{0,20000}", body, re.S)
    excerpt = (m.group(0) if m else body)[:24000]

OUT.write_text(json.dumps({
    "index_status":status,
    "index_sha256":hashlib.sha256(raw).hexdigest(),
    "selected_label":label,\n    "discovery":discovery,
    "pdf_url":pdf_url,
    "pdf_status":pdf_status,
    "pdf_bytes":len(pdf_raw),
    "pdf_sha256":hashlib.sha256(pdf_raw).hexdigest(),
    "content_type":pdf_headers.get("Content-Type"),
    "pdftotext_available":has_pdftotext,
    "extract_ok":extract_ok,
    "text_excerpt":excerpt,
},ensure_ascii=False,indent=2),encoding="utf-8")
print(OUT.read_text(encoding="utf-8")[:4000])
