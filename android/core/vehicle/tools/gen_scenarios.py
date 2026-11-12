"""데모 시나리오 JSON 생성기 (core/vehicle/src/main/assets/scenarios).

기준점(지명, 위도, 경도, 속도, 기어)만 적으면 각 점의 t(출발 후 초)를
구간 거리 / 양 끝 속도 평균으로 계산해 위치 이동과 속도가 맞도록 만든다. 좌표는 근사값.
사용: python gen_scenarios.py ../src/main/assets/scenarios
"""
import json, math, sys

R = 6371.0

def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))

def build(meta, pts):
    out, t, total = [], 0.0, 0.0
    for i, (label, lat, lng, v, gear) in enumerate(pts):
        if i > 0:
            p = pts[i - 1]
            d = hav((p[1], p[2]), (lat, lng))
            total += d
            mean = (p[3] + v) / 2
            t += 10 if d == 0 else d / mean * 3600
        out.append({"t": round(t), "lat": lat, "lng": lng, "speedKmh": v, "gear": gear, "label": label})
    meta = dict(meta, points=out)
    print(meta["id"], "km=%.1f" % total, "arrive_s=%d" % out[-1]["t"], "h=%.2f" % (out[-1]["t"] / 3600), file=sys.stderr)
    return meta

seoul = build(
    {"id": "seoul_gangneung", "name": "서울 → 강릉", "destinationName": "강릉 경포해변",
     "startLocalTime": "09:30", "parkHoldSec": 360},
    [
        ("서울시청", 37.5663, 126.9779, 0, "PARK"),
        ("서울시청 출발", 37.5663, 126.9779, 0, "DRIVE"),
        ("남산1호터널", 37.5530, 126.9890, 30, "DRIVE"),
        ("한남대교 북단", 37.5340, 127.0090, 30, "DRIVE"),
        ("한남IC", 37.5180, 127.0200, 45, "DRIVE"),
        ("양재IC", 37.4690, 127.0390, 50, "DRIVE"),
        ("판교IC", 37.3950, 127.1050, 80, "DRIVE"),
        ("신갈JC", 37.2830, 127.1090, 50, "DRIVE"),
        ("마성IC", 37.2620, 127.1830, 35, "DRIVE"),
        ("용인IC", 37.2470, 127.2160, 60, "DRIVE"),
        ("양지IC", 37.2320, 127.2880, 85, "DRIVE"),
        ("덕평IC", 37.2440, 127.3880, 100, "DRIVE"),
        ("이천IC", 37.2280, 127.4650, 100, "DRIVE"),
        ("여주JC", 37.2580, 127.6130, 95, "DRIVE"),
        ("여주IC", 37.2860, 127.6450, 100, "DRIVE"),
        ("문막IC", 37.3110, 127.8160, 105, "DRIVE"),
        ("만종JC", 37.3560, 127.8960, 90, "DRIVE"),
        ("원주IC", 37.3700, 127.9400, 100, "DRIVE"),
        ("새말IC", 37.4120, 128.0830, 100, "DRIVE"),
        ("둔내IC", 37.5050, 128.2180, 90, "DRIVE"),
        ("면온IC", 37.5600, 128.3360, 95, "DRIVE"),
        ("평창IC", 37.5770, 128.3930, 95, "DRIVE"),
        ("속사IC", 37.6170, 128.4600, 90, "DRIVE"),
        ("진부IC", 37.6420, 128.5600, 95, "DRIVE"),
        ("대관령IC", 37.6780, 128.7130, 80, "DRIVE"),
        ("대관령 내리막", 37.7200, 128.8100, 70, "DRIVE"),
        ("강릉JC", 37.7530, 128.8500, 60, "DRIVE"),
        ("강릉IC", 37.7620, 128.8720, 40, "DRIVE"),
        ("강릉 시내", 37.7700, 128.8980, 30, "DRIVE"),
        ("경포호", 37.7950, 128.9000, 30, "DRIVE"),
        ("경포해변 주차장", 37.8040, 128.9060, 0, "PARK"),
    ],
)

mart = build(
    {"id": "neighborhood_mart", "name": "동네 마트", "destinationName": "동네 마트",
     "startLocalTime": "17:30", "parkHoldSec": 360},
    [
        ("집", 37.5000, 127.0300, 0, "PARK"),
        ("집 출발", 37.5000, 127.0300, 0, "DRIVE"),
        ("골목길", 37.5012, 127.0335, 15, "DRIVE"),
        ("큰길 합류", 37.5040, 127.0360, 25, "DRIVE"),
        ("사거리 신호", 37.5080, 127.0410, 30, "DRIVE"),
        ("대로", 37.5130, 127.0470, 35, "DRIVE"),
        ("마트 앞", 37.5160, 127.0520, 20, "DRIVE"),
        ("마트 주차장", 37.5168, 127.0531, 0, "PARK"),
    ],
)

outdir = sys.argv[1]
for s in (seoul, mart):
    with open(f"{outdir}/{s['id']}.json", "w", encoding="utf-8") as f:
        head = {k: v for k, v in s.items() if k != "points"}
        out = ["{"] + ["  %s: %s," % (json.dumps(k), json.dumps(v, ensure_ascii=False)) for k, v in head.items()]
        out.append('  "points": [')
        pts = [json.dumps(q, ensure_ascii=False) for q in s["points"]]
        out += ["    %s," % q for q in pts[:-1]] + ["    " + pts[-1], "  ]", "}"]
        f.write(chr(10).join(out) + chr(10))
