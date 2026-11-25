from datetime import date

from conftest import AUTH
from shc import queries, report


def ev(i, start, tags, note=None, deleted=False):
    d = {"tags": tags}
    if note: d["note"] = note
    if deleted: d["deleted"] = True
    return {"type": "journal", "id": f"j{i}", "start": start, "zone_offset": "-05:00", "data": d}


def test_journal_ingest_query_and_report(client):
    recs = [ev(1, "2026-11-24T21:30:00-05:00", ["alcool"], "2 verres"),
            ev(2, "2026-11-24T08:00:00-05:00", ["cafe_tardif"]),                 # hier matin : hors rapport
            ev(3, "2026-11-25T07:10:00-05:00", ["malade"]),
            ev(4, "2026-11-24T22:00:00-05:00", ["stress"], deleted=True)]        # supprimé dans l'app
    assert client.post("/ingest", json={"records": recs}, headers=AUTH).status_code == 200
    store = client.app.state.store
    j = queries.journal(store, date(2026, 11, 24), date(2026, 11, 25))["events"]
    assert [e["tags"] for e in j] == [["café tardif"], ["alcool"], ["malade"]]
    assert j[1]["note"] == "2 verres"
    f = report.facts(store, date(2026, 11, 25))
    assert f["journal"] == [{"time": "hier 21:30", "tags": ["alcool"], "note": "2 verres"},
                            {"time": "ce matin 07:10", "tags": ["malade"]}]
    assert "Noté dans le journal : alcool (hier 21:30) ; malade (ce matin 07:10)." in report.fallback_text(f)
