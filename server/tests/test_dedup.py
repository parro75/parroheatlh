from datetime import date

from shc.db import ALL_TYPES

# Valeurs FICTIVES : jamais de données réelles dans les tests.


def w(id_, start, value, source="samsung_export", rec_type="weight", field="kg"):
    return {"type": rec_type, "id": id_, "start": start, "zone_offset": "-04:00",
            "data": {field: value, "source": source}}


def test_duplicate_weighings_are_read_once(client):
    store = client.app.state.store
    store.upsert([
        w("a", "2020-06-10T08:00:00-04:00", 72.0), w("b", "2020-06-10T08:00:00-04:00", 72.0),
        w("c", "2020-06-10T08:00:30-04:00", 72.0),                                   # 3 fois la même pesée
        w("d", "2020-06-10T10:10:00-04:00", 75.0),                                   # autre valeur : gardée
        w("e", "2024-03-02T07:16:00-04:00", 70.5), w("f", "2024-03-02T07:17:00-04:00", 70.1),  # double pesée
        w("g", "2024-03-02T07:16:00-04:00", 70.5, source="com.example.scale"),       # autre source : gardée
        w("h", "2024-03-02T07:40:00-04:00", 70.5),                                   # 24 min plus tard : gardée
    ], "test", allowed=ALL_TYPES)
    ids = sorted(r.id for r in store.fetch("weight", date(2020, 1, 1), date(2024, 12, 31)))
    assert ids == ["a", "d", "e", "f", "g", "h"] or ids == ["b", "d", "e", "f", "g", "h"]


def test_body_fat_deduplicated_too(client):
    store = client.app.state.store
    store.upsert([w("x", "2023-07-24T06:59:00-04:00", 21.0, rec_type="body_fat", field="percent"),
                  w("y", "2023-07-24T07:00:00-04:00", 21.0, rec_type="body_fat", field="percent")],
                 "test", allowed=ALL_TYPES)
    assert len(store.fetch("body_fat", date(2023, 7, 1), date(2023, 7, 31))) == 1
