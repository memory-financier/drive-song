"""숏폼 v2 (세로 1080x1920, 58초): AI 장면(S1~S5) + 실제 앱 화면 + 앱이 만든 노래 + 자막. 콘티 2절.
python plan_short.py <작업 폴더> <AI 장면 폴더>  ->  <작업 폴더>/plan_short.json, clips_short/*.mp4, audio_short.wav
python render.py <작업 폴더>/plan_short.json out.mp4

작업 폴더 (2026-11-13 촬영, video-ai/main-v1): r1~r3.mp4 (에뮬레이터 녹화), stills/ (노래 만드는 동안 스크린샷),
conversation.m4a (앱의 데모 대화), song.m4a (앱이 만든 노래), no_vocals.wav (고른 후보 곡의 반주, Demucs).
AI 장면 폴더: Higgsfield 로 만든 S1~S5 (720x1280, 소리 없음).

부른 사람 자막은 서버가 실제로 배정한 기록(voice-lab assignment)에 완성곡의 앞머리(1.343초)를 더한 시각이다.
완성곡에서 줄마다 목소리를 다시 판별해 9줄 모두 배정과 같음을 확인했다 (timing.json).
"""
import json
import re
import subprocess
import sys
from pathlib import Path

from render import C

work, ai = Path(sys.argv[1]), Path(sys.argv[2])
clips = work / "clips_short"
clips.mkdir(exist_ok=True)
W, H = 1080, 1920
AI = {  # 콘티 S1~S5
    "S1": "Inside-a-Hyundai-Grandeur-sedan-driving.mp4",
    "S2": "Golden-hour-at-Gyeongpo-beach-in-Gangneu.mp4",
    "S3": "Close-up-inside-a-modern-luxury-sedan-t_no-emblem.mp4",  # 핸들의 다른 회사 엠블럼을 지운 판
    "S4": "Parked-by-the-sea-at-sunset-inside-a-Hy.mp4",
    "S5": "Dusk-at-Gyeongpo-beach-A-family-of-thre.mp4",
}
CARD = dict(x=40, y=400, w=1000, h=719)  # 앱 화면 카드 (녹화 앱 영역 1254x902 비율)
CROP = "crop=1254:902:642:70"
TOP = dict(z0=[124, 0, 1254, 813])  # 왼쪽 메뉴와 빈 아래쪽을 빼고 조금 크게
LEAD = 1.343  # 완성곡에서 원곡이 시작하는 시각 (앞에 첫째의 한마디가 들어간다)
el, screens, audio = [], [], []


def text(start, end, s, y, size, color="text", x=W / 2, anchor="c", width=W - 120, **kw):
    el.append(dict(kind="text", start=start, end=end, text=s, x=x, y=y, size=size, color=C[color], anchor=anchor,
                   width=width, **kw))


def pill(start, end, s, y, size, fg, bg, x=W / 2, anchor="c", **kw):
    el.append(dict(kind="pill", start=start, end=end, text=s, x=x, y=y, size=size, fg=C[fg], bg=C[bg], anchor=anchor, **kw))


def shade(start, end, top=True, bottom=True):
    if top:
        el.append(dict(kind="grad", start=start, end=end, box=[0, 0, W, 560], a0=0.8, a1=0.0))
    if bottom:
        el.append(dict(kind="grad", start=start, end=end, box=[0, 1000, W, H], a0=0.0, a1=0.9))


def cut(name, src, start, dur, vf):
    out = clips / f"{name}_{start}_{dur:.2f}.mp4"
    if not out.exists():
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start}", "-i", str(src), "-t", f"{dur}",
                        "-vf", vf, "-an", "-c:v", "libx264", "-crf", "14", "-preset", "fast", str(out)], check=True)
    return out.relative_to(work).as_posix()


def scene(sid, at, dur, src_start=0.0, speed=1.0, fade=0.3):
    """AI 장면을 화면 가득. speed < 1 이면 느리게."""
    vf = f"setpts={1 / speed:.4f}*PTS,fps=30,scale={W}:{H}"
    clip = cut(sid, ai / AI[sid], src_start, dur, vf)  # -t 는 늘린 뒤(출력) 길이
    screens.append(dict(id=sid, clip=clip, start=at, end=at + dur, x=0, y=0, w=W, h=H, radius=0, fade=fade))


def app(name, src, at, src_start, dur, fade=0.2, **kw):
    clip = cut(name, work / src, src_start, dur, f"fps=30,{CROP}")
    screens.append(dict(id=name, clip=clip, start=at, end=at + dur, fade=fade, **CARD, **kw))


def speech(src_start, dur, at, target_db=-17.0):
    """대화 한 조각. 사람마다 녹음 음량이 달라 평균 음량을 재서 맞춘다."""
    out = subprocess.run(["ffmpeg", "-hide_banner", "-ss", f"{src_start}", "-t", f"{dur}", "-i", str(work / "conversation.m4a"),
                          "-af", "volumedetect", "-f", "null", "-"], capture_output=True, text=True).stderr
    mean = float(re.search(r"mean_volume: (-?[\d.]+) dB", out).group(1))
    audio.append(("conversation.m4a", src_start, dur, at, round(target_db - mean, 1), 0.04, 0.12))


who_color = {"엄마": C["mom"], "아빠": C["dad"], "첫째": C["kid"]}

# ---------- A. 차 안 대화 (S1). 앱의 데모 대화(가상의 가족, 합성 음성)에서 가사가 된 말들
A1 = 10.5
scene("S1", 0.0, A1, speed=8.0 / A1)
shade(0.0, A1)
pill(0.3, A1, "대화 담는 중", 110, 40, "rec_text", "rec_bg", dot=C["rec_dot"], outline=C["rec_line"])
text(0.4, A1, "서울 → 강릉 가는 차 안", 225, 46, "sub", bold=False)
talk = [  # (영상 시각, 대화 파일 시작, 길이, 누구, 말, 강조)
    (0.4, 8.80, 2.20, "첫째", "아빠, 강릉까지 얼마나 걸려?", "강릉까지 얼마나 걸려?"),
    (2.8, 12.72, 1.50, "아빠", "안 막히면 세 시간쯤?", None),
    (4.5, 22.24, 1.95, "엄마", "엄마는 따뜻한 커피 한 잔.", "따뜻한 커피 한 잔"),
    (6.7, 37.36, 2.60, "첫째", "저 빨간 산은 꼭 딸기 아이스크림 같아!", "딸기 아이스크림"),
]
for i, (at, src, dur, who, said, hl) in enumerate(talk):
    b = dict(kind="bubble", start=at, end=A1, who=who, color=who_color[who], x=60 if i % 2 == 0 else 150,
             y=990 + i * 165, size=40, width=860, text=said)
    if hl:
        b["hl"] = hl
    el.append(b)
    speech(src, dur, at)
text(8.7, A1, "이 대화가…", 1720, 96)

# ---------- B. 앱이 노래를 만든다 (빨리 감기, 실제 앱 화면)
B0, B1 = A1, 16.0
text(B0, B1, "…노래가 된다면?", 170, 96, width=1000)
stills = sorted((work / "stills").glob("*.png"))
groups = [([p for p in stills if p.stem < "170034"], 0.7),  # 차 안 대화를 가사로 옮기고 있어요
          ([p for p in stills if "170034" <= p.stem < "170142"], 1.1),  # 노래를 만들고 있어요
          ([p for p in stills if "170142" <= p.stem < "170358"], 1.4)]  # 가족 목소리를 입히고 있어요
lst = clips / "stills.txt"
with lst.open("w", encoding="utf-8") as f:
    for ps, dur in groups:
        for p in ps:
            f.write(f"file '{p.as_posix()}'\nduration {dur / len(ps):.4f}\n")
    f.write(f"file '{groups[-1][0][-1].as_posix()}'\n")
st = clips / "stills.mp4"
if not st.exists():
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(lst), "-vf",
                    "crop=1672:1203:856:93,scale=1254:902,fps=30", "-c:v", "libx264", "-crf", "14", str(st)], check=True)
app("drive", "r1.mp4", B0, 82.0, 1.2, **TOP)
screens.append(dict(id="stills", clip="clips_short/stills.mp4", start=B0 + 1.2, end=B0 + 4.4, fade=0.12, **CARD, **TOP))
app("ready", "r2.mp4", B0 + 4.4, 0.0, B1 - B0 - 4.4, **TOP)
text(B0 + 0.2, B1, "실제 앱 화면 (Pleos 에뮬레이터) · 빨리 감기", 1140, 32, "sub", bold=False)
steps = [(B0, B0 + 1.2, "대화 담는 중", "sub"), (B0 + 1.2, B0 + 1.9, "① 대화를 가사로", "text"),
         (B0 + 1.9, B0 + 3.0, "② 노래 만들기", "text"), (B0 + 3.0, B0 + 4.4, "③ 가족 목소리 입히기", "text"),
         (B0 + 4.4, B1, "④ 완성!", "accent")]
for a, b, s, col in steps:
    text(a, b, s, 1230, 76, col)
text(B0 + 1.2, B0 + 3.0, "“강릉까지 얼마나 걸려?” → 그대로 가사로", 1380, 40, "sub", bold=False)
text(B0 + 3.0, B0 + 4.4, "첫째 · 엄마 · 아빠 목소리로 나눠 불러요", 1380, 44, "sub", bold=False)

# ---------- C. 도착 -> P단 -> 자동 재생, 가족이 부르는 노래
C1 = B1
scene("S2", C1, 2.6, src_start=0.6)
shade(C1, C1 + 2.6, bottom=False)
text(C1 + 0.2, C1 + 2.6, "강릉 경포해변 도착", 190, 64)
C2 = C1 + 2.6
scene("S3", C2, 2.46, src_start=0.8)
shade(C2, C2 + 2.46, bottom=False)
text(C2 + 0.15, C2 + 2.46, "주차(P)하는 순간", 160, 64)
text(C2 + 1.0, C2 + 2.46, "노래가 시작돼요", 260, 64, "accent")
C3 = C2 + 2.46  # 실제 앱: 자동 재생 순간 (r3 58.7초 = C3 + 1.0)
app("autoplay", "r3.mp4", C3, 57.7, 3.0, **TOP)
text(C3, C3 + 3.0, "도착하면 자동 재생", 170, 80)
text(C3, C3 + 3.0, "실제 앱 화면 (Pleos 에뮬레이터)", 1140, 32, "sub", bold=False)
P = C3 + 1.0
SONG0 = 12.9  # 완성곡에서 쓰기 시작하는 곳 (첫째가 부르기 바로 앞). 곡 앞 11.6초(전주)는 반주로 깐다
audio.append(("no_vocals.wav", 0.0, SONG0 - LEAD, P - (SONG0 - LEAD), -11.0, 0.8, 0.05))
C4 = C3 + 3.0
scene("S4", C4, 8.0)
shade(C4, C4 + 8.0)
pill(C4 + 0.4, C4 + 3.6, "실내 조명도 노래 색으로", 270, 34, "text", "card", outline=C["line"])
C5 = C4 + 8.0
app("playing", "r3.mp4", C5, 81.0, 6.0, **TOP)
app("lyrics", "r3.mp4", C5 + 6.0, 106.5, 11.44, **TOP)
D0 = C5 + 17.44
text(C4, D0, "우리 가족이 부르는 노래", 150, 72)
text(C5, D0, "실제 앱 화면 · 보관함에서 가사도 볼 수 있어요", 1140, 30, "sub", bold=False)


def vt(s):
    """완성곡 시각 -> 영상 시각."""
    return P + s - SONG0


def sing(a, b, who):
    pill(vt(a), vt(b), f"{who} 목소리", 1270, 40, "text", "card", dot=who_color[who], outline=C["line"])


def lyric(a, b, s, tag=None):
    text(vt(a), vt(b), s, 1400, 56)
    if tag:
        pill(vt(a) + 0.3, vt(b), tag, 1545, 34, "accent", "bg", outline=C["accent"])


# 부른 사람: 서버 배정 기록 + 앞머리 (timing.json 의 phrases). 가사: 잡의 가사 그대로
sing(13.76, 17.18, "첫째")
lyric(13.76, 17.5, "서울을 출발해 강릉으로 가요")
sing(19.16, 22.61, "엄마")
lyric(19.16, 22.7, "아침 햇살에 맑은 하늘이 반짝여요")
pill(vt(22.85), vt(24.15), "엄마가 실제로 한 말", 1270, 40, "accent", "card", dot=who_color["엄마"], outline=C["accent"])
text(vt(22.85), vt(24.15), "“하늘이 완전 파래!”", 1400, 56, "sub")
sing(24.32, 31.82, "아빠")
lyric(24.32, 26.65, "“강릉까지 얼마나 걸려?”", "첫째가 한 말에서 온 가사")
lyric(26.65, 32.0, "아이의 물음에 아빠는 웃으며 대답해요")
sing(33.35, 37.40, "첫째")
lyric(33.35, 37.7, "따뜻한 커피 한 잔 엄마의 미소", "엄마가 한 말에서 온 가사")
sing(38.33, 42.92, "엄마")
lyric(38.33, 43.1, "“저 빨간 산은 딸기 아이스크림 같아”", "첫째가 한 말에서 온 가사")

# ---------- D. 엔딩 (S5)
END = D0 + 8.5
scene("S5", D0, 8.5, speed=6.0 / 8.5)
shade(D0, END)
E0 = vt(43.1) + 0.2  # 엄마 줄이 끝난 뒤 제목
sing(43.82, min(47.87, SONG0 + END - 1.2 - P), "아빠")  # 엔딩 위에서도 누가 부르는지는 그대로 표시
text(E0, END, "오늘의 드라이브 송", 150, 100)
text(E0 + 0.2, END, "차 안의 대화가 가족의 노래가 됩니다", 290, 46, "sub", bold=False)
pill(E0 + 0.4, END, "#HMG차량용앱개발공모전", 1420, 42, "accent", "card", outline=C["accent"])
text(E0 + 0.4, END, "Pleos Connect 차량용 앱 · 팀 Memory Financier", 1530, 34, bold=False)
for i, s in enumerate(["일부 장면은 AI로 생성했습니다 (차 · 인물 · 풍경)",
                       "실제 앱 화면: Pleos 에뮬레이터 녹화 (일부 빨리 감기)",
                       "노래 · 가사 · 가족 목소리: 앱이 자체 GPU 서버에서 만든 결과",
                       "데모 가족: 가상의 가족 (합성 음성, CosyVoice 3)",
                       "목소리 샘플: Zeroth-Korean (CC BY 4.0, Lucas Jo · Wonkyum Lee)"]):
    text(E0 + 0.6, END, s, 1625 + i * 42, 27, "sub", bold=False)
audio.append(("song.m4a", SONG0, END - P, P, 0.0, 0.05, 3.0))

# ---------- 소리
args, chains = ["ffmpeg", "-v", "error", "-y"], []
for i, (src, s, dur, at, db, fi, fo) in enumerate(audio):
    args += ["-ss", f"{s}", "-t", f"{dur}", "-i", str(work / src)]
    f = f"[{i}:a]aformat=sample_rates=48000:channel_layouts=stereo,volume={db}dB,afade=t=in:d={fi}"
    f += f",afade=t=out:st={max(0.0, dur - fo):.3f}:d={fo}"
    ms = int(at * 1000)
    chains.append(f"{f},adelay={ms}|{ms}[a{i}]")
mix = "".join(f"[a{i}]" for i in range(len(audio)))
graph = ";".join(chains) + f";{mix}amix=inputs={len(audio)}:normalize=0,alimiter=limit=0.89,apad[out]"
subprocess.run(args + ["-filter_complex", graph, "-map", "[out]", "-t", f"{END}", "-ar", "48000",
                       str(work / "audio_short.wav")], check=True)

plan = dict(size=[W, H], duration=END, audio="audio_short.wav", screens=screens, elements=el)
(work / "plan_short.json").write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"{END:.1f}s, {len(el)} elements, {len(screens)} screens; P={P:.2f} C4={C4:.2f} C5={C5:.2f} D0={D0:.2f} E0={E0:.2f}")
print("audio:", [(a[0], a[1], a[3], a[4]) for a in audio])
