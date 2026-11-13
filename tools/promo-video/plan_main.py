"""본편 v1 (가로 1920x1080, 약 2분 28초): 실제 앱 화면 녹화 + 앱이 만든 노래 + 자막·그래픽.
python plan_main.py <작업 폴더>  ->  <작업 폴더>/plan_main.json, clips/*.mp4, audio.wav
python render.py <작업 폴더>/plan_main.json out.mp4

작업 폴더 (git 밖): r1.mp4·r2.mp4·r3.mp4 (Pleos 에뮬레이터 screenrecord 1920x1080), stills/*.png (노래 만드는 동안 2560x1440
스크린샷, 파일 이름 HHMMSS_mmm), conversation.m4a (앱의 데모 대화), song.m4a (앱이 만든 완성곡),
no_vocals.wav (고른 후보 곡의 반주, Demucs).
녹화 안의 시각은 2026-11-13 촬영본 기준 (docs/video-storyboard.md 1-0절).

부른 사람 자막은 서버가 실제로 배정한 기록(voice-lab assignment)에 완성곡의 앞머리(1.343초)를 더한 시각이다.
완성곡에서 줄마다 목소리를 다시 판별해 9줄 모두 배정과 같음을 확인했다 (timing.json).
"""
import json
import re
import subprocess
import sys
from pathlib import Path

from render import C

work = Path(sys.argv[1])
clips = work / "clips"
clips.mkdir(exist_ok=True)
W, H = 1920, 1080
APP = dict(x=60, y=118, w=1200, h=863)  # 앱 화면 자리 (녹화 앱 영역 1254x902 의 0.957 배)
CROP = "crop=1254:902:642:70"  # 1920x1080 녹화에서 앱 영역
TOP = dict(z0=[124, 0, 1254, 813])  # 왼쪽 메뉴와 빈 아래쪽을 빼고 조금 크게
LIB = TOP
RX, RW = 1320, 545  # 오른쪽 설명 칸
el, screens = [], []


def text(start, end, s, y, size, color="text", x=RX, width=RW, **kw):
    el.append(dict(kind="text", start=start, end=end, text=s, x=x, y=y, size=size, color=C[color], width=width, **kw))


def pill(start, end, s, y, size, fg, bg, x=RX, **kw):
    el.append(dict(kind="pill", start=start, end=end, text=s, x=x, y=y, size=size, fg=C[fg], bg=C[bg], **kw))


def label(start, end, s):
    pill(start, end, s, APP["y"], 30, "accent", "card", outline=C["accent"])


def cut(name, src, start, dur, vf=CROP):
    name = f"{name}_{start}_{dur:.2f}"
    out = clips / f"{name}.mp4"
    if not out.exists():
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start}", "-i", str(work / src), "-t", f"{dur}",
                        "-vf", f"fps=30,{vf}", "-an", "-c:v", "libx264", "-crf", "14", "-preset", "fast", str(out)], check=True)
    return f"clips/{name}.mp4"


def screen(name, src, at, src_start, dur, fade=0.25, **kw):
    box = dict(APP, **{k: kw.pop(k) for k in ("x", "y", "w", "h") if k in kw})
    vf = kw.pop("vf", CROP)
    screens.append(dict(id=name, clip=cut(name, src, src_start, dur, vf), start=at, end=at + dur, fade=fade, **box, **kw))


def app_caption(start, end, s="실제 앱 화면 · Pleos Connect 에뮬레이터 녹화"):
    text(start, end, s, APP["y"] + APP["h"] + 18, 26, "sub", x=APP["x"], width=APP["w"], bold=False, rise=0)


def speech_gain(src_start, dur, target_db=-17.0):
    """대화 한 조각의 평균 음량을 재서 맞출 dB. 사람마다 녹음 음량이 다르다 (엄마가 가장 작다)."""
    out = subprocess.run(["ffmpeg", "-hide_banner", "-ss", f"{src_start}", "-t", f"{dur}", "-i", str(work / "conversation.m4a"),
                          "-af", "volumedetect", "-f", "null", "-"], capture_output=True, text=True).stderr
    return round(target_db - float(re.search(r"mean_volume: (-?[\d.]+) dB", out).group(1)), 1)


# ---------- 0. 제목 (0~6초)
T1 = 6.0
text(0.3, T1, "차 안의 대화가", 300, 84, x=W / 2, anchor="c", width=1600)
text(0.6, T1, "가족 목소리로 부르는 노래가 된다", 410, 84, "accent", x=W / 2, anchor="c", width=1700)
text(1.6, T1, "오늘의 드라이브 송 · Pleos Connect 차량용 앱", 600, 40, "sub", x=W / 2, anchor="c", width=1600, bold=False)
text(2.2, T1, "실제 앱 시연 (Pleos Connect 에뮬레이터)", 690, 32, "sub", x=W / 2, anchor="c", width=1600, bold=False)

# ---------- 1. 출발 전 (정차 중): 가족 등록 -> 탑승자·대화 담기 -> 주행 기록 시작
# 처음 2.8초는 차량 화면 전체를 보여 주고 앱 쪽으로 다가간다
screens.append(dict(id="full", clip=cut("full", "r1.mp4", 17.6, 2.8, "scale=1920:1080"), start=T1, end=T1 + 2.8,
                    x=0, y=0, w=W, h=H, z0=[0, 0, 1920, 1080], z1=[320, 90, 1920, 990], linear=True, fade=0.3))
pill(T1 + 0.3, T1 + 2.8, "Pleos Connect 차량 화면 (에뮬레이터)", 965, 32, "text", "card", x=W / 2, anchor="c", outline=C["line"])
S1 = T1 + 2.8
screen("family", "r1.mp4", S1, 20.4, 6.2)
S2 = S1 + 6.2
screen("passenger", "r1.mp4", S2, 34.6, 8.0)
S3 = S2 + 8.0
screen("start", "r1.mp4", S3, 47.6, 5.4)
S4 = S3 + 5.4
app_caption(S1, S4)
label(S1, S4, "① 출발 전 · 정차 중")
text(S1, S2, "가족 등록", 200, 54)
for i, s in enumerate(["호칭과 목소리 클립 (약 5초)", "대화 녹음 동의", "노래 목소리 변환 동의", "14세 미만은 보호자 동의"]):
    pill(S1 + 0.4 + i * 0.35, S2, s, 300 + i * 84, 30, "text", "card", dot=C["accent"], outline=C["line"])
text(S1 + 1.5, S2, "영상 속 가족은 합성 음성으로 만든 가상의 가족 (엄마 · 아빠 · 첫째)", 680, 28, "sub", bold=False)
text(S2, S3, "이번 주행 탑승자 고르고 대화 담기 켜기", 200, 50)
for i, s in enumerate(["탑승자 모두 동의했을 때만 켜져요", "이번 주행만 · 언제든 끄기", "등록하지 않은 사람이 타면 꺼요"]):
    pill(S2 + 0.5 + i * 0.35, S3, s, 380 + i * 84, 30, "text", "card", dot=C["accent"], outline=C["line"])
text(S3, S4, "주행 기록 시작", 200, 54)
text(S3 + 0.3, S4, "이제 운전만 하면 돼요", 290, 38, "sub", bold=False)

# ---------- 2. 주행 중: 대화 담기 (앱의 데모 대화에서 가사가 된 말들, 약 24초)
D0 = S4
convo = [  # (대화 파일 구간, 누가, 말풍선, 강조)
    (8.80, 10.96, "첫째", "아빠, 강릉까지 얼마나 걸려?", "강릉까지 얼마나 걸려?"),
    (11.60, 16.25, "아빠", "음, 안 막히면 세 시간쯤? 중간에 휴게소도 들를 거야.", "안 막히면 세 시간쯤?"),
    (20.65, 24.15, "엄마", "호두과자 좋지. 엄마는 따뜻한 커피 한 잔.", "따뜻한 커피 한 잔"),
    (37.36, 39.95, "첫째", "저 빨간 산은 꼭 딸기 아이스크림 같아!", "딸기 아이스크림"),
    (55.82, 59.55, "엄마", "모래사장 걷고, 저녁엔 다 같이 조개구이 먹자.", "조개구이"),
    (60.36, 63.70, "첫째", "나는 모래성 크게 쌓을 거야. 아빠도 같이 하자.", "모래성 크게"),
]
who_color = {"엄마": C["mom"], "아빠": C["dad"], "첫째": C["kid"]}
audio = []  # (파일, 시작, 길이, 놓을 시각, dB, 페이드 인, 페이드 아웃)
t = D0 + 0.8
for s, e, who, line, hl in convo:
    audio.append(("conversation.m4a", s, e - s, t, speech_gain(s, e - s), 0.04, 0.12))
    el.append(dict(kind="bubble", start=t, end=t + (e - s) + 0.3, who=who, color=who_color[who], x=RX, y=420, size=32,
                   width=470, text=line, hl=hl))
    t += (e - s) + 0.45
D1 = t + 0.3
screen("drive", "r1.mp4", D0, 69.5, D1 - D0, **TOP)
app_caption(D0, D1, "실제 앱 화면 · 데모: 서울 → 강릉 3시간 주행을 약 9분으로 재생")
label(D0, D1, "② 주행 중")
text(D0, D1, "운전하는 사람은 아무것도 하지 않아요", 190, 44)
text(D0 + 0.4, D1, "앱이 차 안 대화를 담아 5분마다 서버에 올려요", 310, 30, "sub", bold=False)
el.append(dict(kind="wave", start=D0 + 0.5, end=D1, y=880, cx=RX + RW / 2, color=C["rec_dot"], height=90, bars=18))
text(D0 + 1.0, D1, "데모 대화: 가상의 가족이 대본을 말해요 (합성 음성)", 950, 24, "sub", bold=False)

# ---------- 3. 도착 10분 전: 자체 GPU 서버가 노래를 만든다 (빨리 감기)
G0 = D1
screen("trigger", "r1.mp4", G0, 98.5, 5.3, **TOP)
# 노래 만드는 동안 찍은 스크린샷: 단계마다 읽을 수 있게 길이를 다르게
stills = sorted((work / "stills").glob("*.png"))
groups = [("2", [p for p in stills if p.stem < "170034"], 2.0),  # 차 안 대화를 가사로 옮기고 있어요
          ("3", [p for p in stills if "170034" <= p.stem < "170142"], 2.5),  # 노래를 만들고 있어요
          ("4", [p for p in stills if "170142" <= p.stem < "170358"], 4.5)]  # 가족 목소리를 입히고 있어요
lst = clips / "stills.txt"
with lst.open("w", encoding="utf-8") as f:
    for _, ps, dur in groups:
        for p in ps:
            f.write(f"file '{p.as_posix()}'\nduration {dur / len(ps):.4f}\n")
    f.write(f"file '{groups[-1][1][-1].as_posix()}'\n")
st = clips / "stills.mp4"
if not st.exists():
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(lst), "-vf",
                    "crop=1672:1203:856:93,scale=1254:902,fps=30", "-c:v", "libx264", "-crf", "14", str(st)], check=True)
G1 = G0 + 5.3
screens.append(dict(id="stills", clip="clips/stills.mp4", start=G1, end=G1 + 9.0, fade=0.15, **APP, **TOP))
G2 = G1 + 9.0
screen("ready", "r2.mp4", G2, 0.0, 3.4, **TOP)
G3 = G2 + 3.4
app_caption(G0, G3, "실제 앱 화면 · 빨리 감기 (실제로는 3분 47초)")
label(G0, G3, "③ 도착 10분 전")
text(G0, G3, "자체 GPU 서버가 노래를 만들어요", 190, 44)
steps = [("① 대화 받아 적기 · 누가 말했는지", "Whisper · ECAPA", G1, G1 + 1.0),
         ("② 대화를 가사로", "Gemma 3", G1 + 1.0, G1 + 2.0),
         ("③ 작곡 · AI 가수가 노래 (후보 3곡)", "ACE-Step 1.5", G1 + 2.0, G1 + 4.5),
         ("④ 한 곡을 골라 가족 목소리 입히기", "Seed-VC", G1 + 4.5, G2),
         ("⑤ 대화 속 한마디 넣고 완성", "", G2, G3)]
for i, (name, tech, a, b) in enumerate(steps):
    y = 330 + i * 108
    el.append(dict(kind="text", start=G0 + 0.2, end=G3, text=name, x=RX, y=y, size=32, color=(90, 110, 140), width=RW))
    el.append(dict(kind="text", start=a, end=b, text=name, x=RX, y=y, size=32, color=C["accent"], width=RW, rise=0))
    el.append(dict(kind="text", start=b, end=G3, text=name, x=RX, y=y, size=32, color=C["text"], width=RW, rise=0))
    if tech:
        text(G0 + 0.2, G3, tech, y + 48, 26, "sub", bold=False)

# ---------- 4. 도착 (P단) -> 자동 재생, 가족이 부르는 노래
A0 = G3
screen("arrive", "r3.mp4", A0, 56.2, 2.5, **TOP)
P = A0 + 2.5  # 자동 재생 순간 (r3 58.7초)
audio.append(("no_vocals.wav", 48.0, P - G0, G0, -14.0, 1.0, 2.0))  # 노래 만드는 동안 ~ 도착 직전 배경음
# 완성곡에서 쓰는 구간: 곡 앞 한마디 / 노래 전체 (전주 9.9초는 건너뜀) / 곡 끝 한마디
songA, songB, songC = (0.0, 3.0), (12.9, 65.5), (75.9, 77.69)
SB = P + songA[1] - songA[0]
SC = SB + songB[1] - songB[0]
END_SONG = SC + songC[1] - songC[0]
V1, V2, V3 = P + 20.0, P + 36.0, P + 50.5  # 홈(재생 중) -> 재생 화면 -> 보관함 가사 -> 홈
screen("playing", "r3.mp4", P, 58.7, V1 - P, fade=0.1, **TOP)
screen("player", "r3.mp4", V1, 80.0, V2 - V1, **TOP)
screen("library", "r3.mp4", V2, 105.0, V3 - V2, **LIB)
screen("home", "r3.mp4", V3, 121.0, END_SONG - V3, **TOP)
audio += [("song.m4a", songA[0], songA[1] - songA[0], P, 0.0, 0.0, 0.4),
          ("song.m4a", songB[0], songB[1] - songB[0], SB - 0.2, 0.0, 0.4, 0.4),
          ("song.m4a", songC[0], songC[1] - songC[0], SC - 0.2, 0.0, 0.4, 0.05)]
app_caption(A0, END_SONG, "실제 앱 화면 · 노래는 앱이 만든 곡 (전주와 후주는 줄임)")
label(A0, V2, "④ 도착 · 기어 P")
pill(A0 + 0.6, P + 2.6, "P  도착 → 자동 재생", 190, 40, "bg", "accent")
text(P + 2.6, V2, "도착하는 순간, 우리 가족 노래", 190, 44)
label(V2, V3, "⑤ 보관함")
text(V2, V3, "보관함: 가사와 부른 가족", 190, 44)
label(V3, END_SONG, "오늘의 노래")
text(V3, END_SONG, "돌아오는 길은 또 다른 노래가 돼요", 190, 40)


def song_t(s):
    """완성곡 시각 -> 영상 시각."""
    for (a, b), at in ((songA, P), (songB, SB), (songC, SC)):
        if a <= s <= b:
            return at + s - a
    raise ValueError(s)


def sing(a, b, who, kind="목소리로 노래"):
    pill(song_t(a), song_t(b), f"{who} {kind}", 330, 32, "text", "card", dot=who_color[who], outline=C["line"])


def lyric(a, b, s, tag=None):
    text(song_t(a), song_t(b), s, 420, 46, width=RW)
    if tag:
        pill(song_t(a) + 0.3, song_t(b), tag, 610, 28, "accent", "bg", outline=C["accent"])


def moment(a, b, who, line):
    pill(song_t(a), song_t(b), f"대화 속 한마디 · {who}가 실제로 한 말", 330, 30, "accent", "bg", outline=C["accent"])
    el.append(dict(kind="bubble", start=song_t(a), end=song_t(b), who=who, color=who_color[who], x=RX, y=420, size=32,
                   width=470, text=line))


# 부른 사람: 서버 배정 기록 + 앞머리 (timing.json 의 phrases). 가사: 잡의 가사 그대로
moment(0.0, 2.6, "첫째", "응! 크게 틀어 줘!")
sing(13.76, 17.18, "첫째")
lyric(13.76, 17.5, "서울을 출발해 강릉으로 가요")
sing(19.16, 22.61, "엄마")
lyric(19.16, 22.7, "아침 햇살에 맑은 하늘이 반짝여요")
moment(22.85, 24.2, "엄마", "하늘이 완전 파래!")
sing(24.32, 31.82, "아빠")
lyric(24.32, 26.65, "“강릉까지 얼마나 걸려?”", "첫째가 한 말에서 온 가사")
lyric(26.65, 32.0, "아이의 물음에 아빠는 웃으며 대답해요")
sing(33.35, 37.40, "첫째")
lyric(33.35, 37.7, "따뜻한 커피 한 잔 엄마의 미소", "엄마가 한 말에서 온 가사")
sing(38.33, 42.92, "엄마")
lyric(38.33, 43.1, "“저 빨간 산은 딸기 아이스크림 같아”", "첫째가 한 말에서 온 가사")
sing(43.82, 47.87, "아빠")
lyric(43.82, 48.1, "“저 빨간 산은 딸기 아이스크림 같아”")
sing(49.22, 51.93, "첫째")
lyric(49.22, 51.93, "모래성 크게 쌓고", "첫째가 한 말에서 온 가사")
sing(51.93, 58.37, "엄마")
lyric(51.93, 58.6, "조개구이 먹으며 우리 가족 행복한 추억 만들어요", "엄마가 한 말에서 온 가사")
sing(59.60, 65.24, "아빠")
lyric(59.60, 65.4, "우리 가족 행복한 추억 만들어요")
moment(76.2, 77.69, "아빠", "안 막히면 세 시간쯤?")

# ---------- 5. 어떻게 만들었나 (카드 3장)
K0 = END_SONG
K1 = K0 + 11.0
text(K0 + 0.2, K1, "어떻게 만들었나", 80, 52, x=W / 2, anchor="c", width=1600)
cards = [("차량 기능 · Pleos SDK", ["기어 · 차속 · 위치로 주행과 도착을 알아요", "Gleo AI 비서: “드라이브 송 틀어줘”",
                                   "말로 하기 (음성 인식)", "도착 조명: 노래 색 앰비언트 라이트"]),
         ("자체 GPU 서버", ["받아 적기 · 화자 구분 (Whisper, ECAPA)", "가사 (Gemma 3 12B)", "작곡 · 노래 (ACE-Step 1.5), 3곡 중 고르기",
                         "가족 목소리 변환 (Seed-VC)", "1곡 약 3.5~4분 (RTX 5070 Ti)"]),
         ("안심", ["동의 3종 · 14세 미만 보호자 동의", "대화 담기는 이번 주행만, 탑승자 모두 동의할 때", "원본 대화는 분석 직후 삭제",
                 "가족 목소리 · 대화를 외부 AI로 보내지 않아요"])]
for i, (title, lines) in enumerate(cards):
    x0 = 60 + i * 610
    a = K0 + 0.5 + i * 0.5
    el.append(dict(kind="rect", start=a, end=K1, box=[x0, 190, x0 + 580, 900], radius=36, color=C["card"]))
    text(a, K1, title, 230, 40, "accent", x=x0 + 40, width=500)
    for j, s in enumerate(lines):
        text(a + 0.2, K1, s, 330 + j * 108, 31, x=x0 + 40, width=500, bold=False)
audio.append(("no_vocals.wav", 52.3, K1 - K0 + 8.0, K0 - 0.3, -13.0, 1.0, 2.5))

# ---------- 6. 엔딩
E1 = K1 + 7.0
text(K1 + 0.2, E1, "오늘의 드라이브 송", 300, 100, x=W / 2, anchor="c", width=1600)
text(K1 + 0.4, E1, "차 안의 대화가 가족의 노래가 됩니다", 440, 44, "sub", x=W / 2, anchor="c", width=1600, bold=False)
pill(K1 + 0.6, E1, "github.com/memory-financier/drive-song", 540, 34, "accent", "card", x=W / 2, anchor="c", outline=C["accent"])
text(K1 + 0.6, E1, "팀 Memory Financier · Pleos Connect 차량용 앱", 640, 32, "sub", x=W / 2, anchor="c", width=1600, bold=False)
for i, s in enumerate(["실제 앱 화면: Pleos Connect 에뮬레이터 녹화 (일부 빨리 감기 · 편집)",
                       "노래 · 가사 · 가족 목소리: 앱이 자체 GPU 서버에서 만든 결과 (전주와 후주는 줄임)",
                       "데모 가족: 가상의 가족 (합성 음성, CosyVoice 3). 목소리 샘플: Zeroth-Korean (CC BY 4.0, Lucas Jo · Wonkyum Lee)"]):
    text(K1 + 0.8, E1, s, 820 + i * 46, 26, "sub", x=W / 2, anchor="c", width=1800, bold=False)
# 제목 · 준비 구간 배경음: 완성곡 반주 (곡 앞부분)
audio.insert(0, ("no_vocals.wav", 2.0, D0 + 0.6, 0.0, -11.0, 1.0, 1.2))
TOTAL = E1

# ---------- 소리: 조각을 놓고 섞는다
args, chains = ["ffmpeg", "-v", "error", "-y"], []
for i, (src, s, dur, at, db, fi, fo) in enumerate(audio):
    args += ["-ss", f"{s}", "-t", f"{dur}", "-i", str(work / src)]
    f = f"[{i}:a]aformat=sample_rates=48000:channel_layouts=stereo,volume={db}dB"
    if fi:
        f += f",afade=t=in:d={fi}"
    if fo:
        f += f",afade=t=out:st={max(0.0, dur - fo):.3f}:d={fo}"
    ms = int(at * 1000)
    chains.append(f"{f},adelay={ms}|{ms}[a{i}]")
mix = "".join(f"[a{i}]" for i in range(len(audio)))
graph = ";".join(chains) + f";{mix}amix=inputs={len(audio)}:normalize=0,alimiter=limit=0.89,apad[out]"
subprocess.run(args + ["-filter_complex", graph, "-map", "[out]", "-t", f"{TOTAL}", "-ar", "48000",
                       str(work / "audio.wav")], check=True)

plan = dict(size=[W, H], duration=TOTAL, audio="audio.wav", screens=screens, elements=el)
(work / "plan_main.json").write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"{TOTAL:.1f}s, {len(el)} elements, {len(screens)} screens, {len(audio)} audio pieces")
print({k: round(v, 1) for k, v in dict(S1=S1, D0=D0, G0=G0, A0=A0, P=P, SB=SB, SC=SC, K0=K0, K1=K1).items()})
