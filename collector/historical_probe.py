import json, pathlib, re
from public_proxy import Collector, PATHS, DATE, actions, identity, same_meeting, text, Document

OUT = pathlib.Path("historical-probe.json")


def structure(html):
    root = Document(html).root
    tables = []
    for ti, table in enumerate(root.all("table")):
        if ti >= 12:
            break
        headers = [text(x) for x in table.all("th")]
        rows = []
        for ri, row in enumerate(table.all("tr")):
            if ri >= 3:
                break
            cells = []
            for cell in list(row.all("td"))[:16]:
                cells.append({
                    "class": cell.attrs.get("class", ""),
                    "text": text(cell)[:240],
                    "children": [
                        {"tag": c.tag, "class": c.attrs.get("class", ""), "text": text(c)[:120]}
                        for c in cell.children if hasattr(c, "tag")
                    ][:8],
                })
            if cells:
                rows.append(cells)
        if headers or rows:
            tables.append({"headers": headers[:24], "rows": rows})
    return {
        "tables": tables,
        "class_counts": class_counts(root),
        "race_units": [
            {
                "id": e.attrs.get("id"),
                "class": e.attrs.get("class", ""),
                "text": text(e)[:1000],
            }
            for e in root.all()
            if "race" in (e.attrs.get("class", "") + " " + e.attrs.get("id", "")).lower()
        ][:30],
    }


def class_counts(root):
    counts = {}
    for e in root.all():
        for c in e.attrs.get("class", "").split():
            if any(k in c.lower() for k in ["race", "horse", "num", "odds", "place", "weight", "sex", "course", "name", "waku"]):
                counts[c] = counts.get(c, 0) + 1
    return dict(sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:80])


def main():
    tmp = pathlib.Path("historical-probe-state.json")
    if tmp.exists():
        tmp.unlink()
    c = Collector(tmp)

    def meetings(html):
        out = []
        for token in actions(html, PATHS["card"]):
            try:
                m = identity(token, "meeting")
            except Exception:
                continue
            if m["date"] == DATE:
                out.append(dict(m, source_cname=token))
        return out

    _, meetings_value = c.request(PATHS["card"], "pw01dli00/F3", meetings)
    if not meetings_value:
        raise SystemExit("NO_MEETINGS")
    meeting = next((m for m in meetings_value if m["venue_code"] == "03"), meetings_value[0])

    def catalog_token(html):
        for token in actions(html, PATHS["card"]):
            try:
                if same_meeting(identity(token, "catalog"), meeting):
                    return token
            except Exception:
                pass
        raise RuntimeError("NO_CATALOG_TOKEN")

    _, catalog_cname = c.request(PATHS["card"], meeting["source_cname"], catalog_token)
    if not catalog_cname:
        raise SystemExit("NO_CATALOG")
    card_rec, card_structure = c.request(PATHS["card"], catalog_cname, structure)

    def result_meeting(html):
        for token in actions(html, PATHS["result"]):
            try:
                if same_meeting(identity(token, "result_meeting"), meeting):
                    return token
            except Exception:
                pass
        raise RuntimeError("NO_RESULT_MEETING")

    _, result_meeting_cname = c.request(PATHS["result"], "pw01sli00/AF", result_meeting)
    if not result_meeting_cname:
        raise SystemExit("NO_RESULT_MEETING_TOKEN")

    def first_result(html):
        candidates = []
        for token in actions(html, PATHS["result"]):
            try:
                r = identity(token, "result")
                if same_meeting(r, meeting):
                    candidates.append((r["race_no"], token))
            except Exception:
                pass
        if not candidates:
            raise RuntimeError("NO_RESULT_TOKEN")
        return sorted(candidates)[0]

    _, result_pair = c.request(PATHS["result"], result_meeting_cname, first_result)
    if not result_pair:
        raise SystemExit("NO_RESULT")
    race_no, result_cname = result_pair
    result_rec, result_structure = c.request(PATHS["result"], result_cname, structure)

    OUT.write_text(json.dumps({
        "date": DATE,
        "meeting": meeting,
        "catalog_cname": catalog_cname,
        "result_cname": result_cname,
        "race_no": race_no,
        "card_raw_sha256": card_rec["raw_sha256"],
        "result_raw_sha256": result_rec["raw_sha256"],
        "card": card_structure,
        "result": result_structure,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
