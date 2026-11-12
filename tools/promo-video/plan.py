"""숏폼 v0 장면 정의 -> plan.json. 시각은 audio.wav 기준 (초)."""
import json
from pathlib import Path

from render import C

W = 1080
A, B, CC, D, END = 0.0, 9.0, 18.4, 40.2, 46.0
el = []


def text(start, end, s, y, size, color="text", anchor="c", x=W / 2, **kw):
    el.append(dict(kind="text", start=start, end=end, text=s, x=x, y=y, size=size, color=C[color], anchor=anchor, **kw))


def pill(start, end, s, y, size, fg, bg, x=W / 2, anchor="c", **kw):
    el.append(dict(kind="pill", start=start, end=end, text=s, x=x, y=y, size=size, fg=C[fg], bg=C[bg], anchor=anchor, **kw))


# A. 차 안 대화 (0~9초)
pill(0.2, B, "대화 담는 중", 190, 40, "rec_text", "rec_bg", dot=C["rec_dot"], outline=C["rec_line"])
text(0.3, B, "서울 → 강릉 가는 차 안", 320, 46, "sub", bold=False)
el.append(dict(kind="bubble", start=0.5, end=B, who="엄마", color=C["mom"], x=70, y=470, size=46, width=820,
               text="지난해 짜장 짬뽕 라면으로 중화요리 전쟁을 벌인 데 이어…", hl="중화요리"))
el.append(dict(kind="bubble", start=4.9, end=B, who="첫째", color=C["kid"], x=150, y=800, size=46, width=780,
               text="이번에 발견된 화석은 에우노토사우루스…", hl="에우노토사우루스"))
el.append(dict(kind="wave", start=0.3, end=B, y=1200, color=C["rec_dot"], height=110))
text(6.4, B, "이 대화가…", 1380, 100)

# B. 앱이 노래를 만든다 (빨리 감기)
FULL, NEAR = (0, 0, 1680, 1197), (140, 0, 140 + 1180, 840)


def lerp(a, b, p):
    return [x + (y - x) * p for x, y in zip(a, b)]


text(B, CC, "…노래가 된다면?", 190, 96, width=1000)
steps = [("b1", B, 12.4, "① 대화를 가사로"), ("b2", 12.4, 14.4, "② 노래 만들기"),
         ("b3", 14.4, 16.4, "③ 가족 목소리 입히기"), ("b4", 16.4, CC, "④ 완성!")]
screens = []
for i, (clip, s, e, label) in enumerate(steps):
    p0, p1 = (s - B) / (CC - B), (e - B) / (CC - B)
    screens.append(dict(id=clip, clip=f"clips/{clip}.mp4", start=s, end=e, x=40, y=400, w=1000, h=712,
                        z0=lerp(FULL, NEAR, p0), z1=lerp(FULL, NEAR, p1), linear=True, fade=0.12))
    text(s, e, label, 1240, 76, "accent" if clip == "b4" else "text")
text(B + 0.2, CC, "실제 앱 화면 (Pleos 에뮬레이터) · 빨리 감기", 1140, 32, "sub", bold=False)
text(9.6, 12.4, "“…중화요리 전쟁을 벌인 데 이어…”", 1380, 40, "sub", bold=False)
text(10.4, 12.4, "→ 중화 요리 전쟁 추억 소환해", 1450, 54, "accent")
text(14.6, 16.4, "엄마 · 첫째 · 아빠 목소리로 나눠 불러요", 1380, 44, "sub", bold=False)

# C. 도착, 가족이 부르는 노래
text(CC, D, "강릉 도착", 165, 46, "sub", bold=False)
text(CC, D, "우리 가족이 부르는 노래", 235, 76, width=1000)
screens.append(dict(id="c1", clip="clips/c1.mp4", start=CC, end=D, x=40, y=400, w=1000, h=712,
                    z0=list(NEAR), z1=[170, 20, 170 + 1100, 20 + 784], fade=0.3))
text(CC + 0.2, D, "실제 앱 화면 (Pleos 에뮬레이터)", 1140, 32, "sub", bold=False)
who_color = {"엄마": C["mom"], "첫째": C["kid"], "아빠": C["dad"]}


def sing(start, end, who):
    pill(start, end, f"{who} 목소리", 1225, 38, "text", "card", dot=who_color[who], outline=C["line"])


lines = [(18.9, 23.8, "엄마", "라디오 켜고 신나게 노래해", None),
         (23.8, 27.9, "첫째", "중화 요리 전쟁 추억 소환해", "엄마가 한 말에서 온 가사"),
         (27.9, 33.1, "첫째", "에우노토사우루스, 신기한 이름", "첫째가 한 말에서 온 가사")]
for s, e, who, lyric, tag in lines:
    sing(s, e, who)
    text(s, e, lyric, 1335, 62, width=1020)
    if tag:
        pill(s + 0.3, e, tag, 1440, 34, "accent", "bg", outline=C["accent"])
sing(33.1, 34.8, "아빠")
sing(34.8, 37.3, "엄마")
text(33.1, 37.3, "경포해변, 설렘 가득해", 1335, 62, width=1020)
text(37.5, D, "부른 가족: 엄마 · 첫째 · 아빠", 1250, 52)
text(37.8, D, "대화 속 한마디도 노래 앞·중간·끝에", 1340, 40, "sub", bold=False)

# D. 엔딩
text(D, END, "오늘의 드라이브 송", 620, 104)
text(D + 0.2, END, "차 안의 대화가 가족의 노래가 됩니다", 780, 46, "sub", bold=False)
pill(D + 0.5, END, "#HMG차량용앱개발공모전", 900, 40, "accent", "card", outline=C["accent"])
text(D + 0.5, END, "Pleos Connect 차량용 앱", 1050, 38, "sub", bold=False)
for i, s in enumerate(["실제 앱 화면: Pleos 에뮬레이터 녹화 (일부 빨리 감기)",
                       "노래·가사·가족 목소리: 앱이 자체 GPU 서버에서 만든 결과",
                       "데모 음성: Zeroth-Korean (CC BY 4.0, Lucas Jo · Wonkyum Lee)"]):
    text(D + 0.8, END, s, 1400 + i * 46, 28, "sub", bold=False)

plan = dict(duration=END, audio="audio.wav", screens=screens, elements=el)
Path(__file__).with_name("plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
print(len(el), "elements,", len(screens), "screens")
