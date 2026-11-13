"""GPU·모델 없이 도는 로직 시험 (설계 §7.5)."""

import numpy as np
import pytest

from voicelab.analyze import REF_MAX_SEC, Segment, match_speaker, pick_moment, pick_reference
from voicelab.audio import voiced_segments
from voicelab.sing import (
    Singer, SingerInput, assign_phrases, candidate_score, compose, fit_length, layered_fraction, lyric_coverage,
    octave_shift, phrase_gains, phrase_weights, split_long, wordless,
)

SR = 16_000


def tone(sec, amp=0.3, freq=220):
    t = np.arange(int(sec * SR)) / SR
    return (amp * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def silence(sec):
    return np.zeros(int(sec * SR), dtype=np.float32)


# --- 발화 나누기 --------------------------------------------------------------

def test_voiced_segments_split_on_gaps_and_join_short_gaps():
    audio = np.concatenate([silence(0.5), tone(2.0), silence(0.2), tone(1.0), silence(0.8), tone(1.5), silence(0.5)])
    segs = voiced_segments(audio, SR)
    assert len(segs) == 2  # 0.2초 틈은 이어지고 0.8초 틈에서 나뉜다
    (s1, e1), (s2, e2) = segs
    assert abs(s1 - 0.5) < 0.05 and abs(e1 - 3.7) < 0.05
    assert abs(s2 - 4.5) < 0.05 and abs(e2 - 6.0) < 0.05


def test_voiced_segments_drop_too_short_and_silence():
    assert voiced_segments(np.concatenate([silence(1), tone(0.2), silence(1)]), SR) == []
    assert voiced_segments(silence(3), SR) == []  # 절대 하한(-60dB) 아래는 말소리가 아니다
    assert voiced_segments(np.zeros(0, np.float32), SR) == []


# --- 누가 말했나 --------------------------------------------------------------

def unit(v):
    v = np.asarray(v, dtype=np.float32)
    return v / np.linalg.norm(v)


def test_match_speaker_picks_closest_member_above_threshold():
    enrolled = {"mom": unit([1, 0, 0]), "dad": unit([0, 1, 0])}
    assert match_speaker(unit([0.9, 0.1, 0]), enrolled, 0.45)[0] == "mom"
    who, score = match_speaker(unit([0.1, 0.1, 1]), enrolled, 0.45)
    assert who is None and score < 0.45  # 모르는 사람(손님)은 버린다
    assert match_speaker(unit([1, 0, 0]), {}, 0.45) == (None, 0.0)


# --- 한마디·참고 목소리 --------------------------------------------------------

def test_pick_moment_prefers_loudest_fitting_utterance():
    conv = np.concatenate([tone(3.0, amp=0.1), silence(1), tone(2.0, amp=0.5), silence(1), tone(8.0, amp=0.9)])
    segs = [Segment(0, 3, "kid"), Segment(4, 6, "kid"), Segment(7, 15, "kid")]
    assert pick_moment(segs, conv, SR) == segs[1]  # 8초짜리는 너무 길어서 제외


def test_pick_moment_trims_when_only_long_utterances():
    conv = tone(20)
    m = pick_moment([Segment(0, 9, "dad", text="긴 문장"), Segment(10, 18, "dad")], conv, SR)
    assert (m.start, m.end, m.text) == (10, 14.5, "")
    assert pick_moment([Segment(0, 0.5, "dad")], conv, SR) is None


def test_pick_reference_takes_best_scored_up_to_limit_in_time_order():
    segs = [Segment(0, 12, "mom", 0.6), Segment(20, 32, "mom", 0.9), Segment(40, 52, "mom", 0.8)]
    ref = pick_reference(segs)
    assert sum(s.duration for s in ref) == pytest.approx(REF_MAX_SEC)
    assert [s.start for s in ref] == [0, 20, 40]  # 시간순으로 이어 붙인다
    assert ref[0].duration == pytest.approx(6)  # 점수가 가장 낮은 발화가 잘린다


# --- 나눠 부르기 --------------------------------------------------------------

@pytest.mark.parametrize("member, singer, expected", [
    (126, 221, -12),  # 11/03 시험: 아빠 말소리 126Hz, 가수 221Hz → 한 옥타브 내림
    (280, 221, 0),  # 아이: 그대로
    (300, 150, 12),  # 남성 가수 → 아이: 한 옥타브 올림
    (None, 221, 0),
    (126, None, 0),
    (30, 500, -24),  # 두 옥타브 넘게는 옮기지 않는다
])
def test_octave_shift_keeps_key(member, singer, expected):
    assert octave_shift(member, singer) == expected
    assert expected % 12 == 0


def test_assign_phrases_round_robin():
    phrases = [(0, 2), (3, 5), (6, 8)]
    assert assign_phrases(phrases, ["mom", "dad"]) == [(0, 2, "mom"), (3, 5, "dad"), (6, 8, "mom")]
    assert assign_phrases(phrases, []) == []
    # 모두 짧으면 그냥 돌아가며
    assert assign_phrases([(0, 1), (2, 3)], ["mom", "dad"]) == [(0, 1, "mom"), (2, 3, "dad")]


def test_assign_phrases_short_bits_do_not_take_a_turn():
    # 앞의 허밍(0.7초)·가운데 끝음(0.5초)은 가까운 줄을 부르는 사람에게 붙고, 줄은 엄마 -> 아빠 -> 첫째 순서 그대로
    phrases = [(1.0, 1.7), (5.0, 8.0), (8.3, 8.8), (9.5, 12.5), (13.0, 16.0)]
    got = assign_phrases(phrases, ["mom", "dad", "kid"])
    assert [w for _, _, w in got] == ["mom", "mom", "mom", "dad", "kid"]


def test_split_long_cuts_at_quietest_point():
    # 4초 + 아주 짧게 작아지는 곳 + 6초 = 10초 한 구간 -> 작아지는 곳(약 4초)에서 둘로
    audio = np.concatenate([tone(4), tone(0.1, amp=0.02), tone(6)])
    parts = split_long([(0.0, 10.1)], audio, SR, max_sec=8.0)
    assert len(parts) == 2 and abs(parts[0][1] - 4.05) < 0.1 and parts[-1][1] == 10.1
    assert split_long([(0.0, 5.0)], audio, SR) == [(0.0, 5.0)]
    # 아주 길면 여러 번 나눠 모두 max_sec 이하
    long = np.concatenate([np.concatenate([tone(3), tone(0.1, amp=0.02)]) for _ in range(8)])
    assert all(e - s <= 8.0 for s, e in split_long([(0.0, len(long) / SR)], long, SR))


def test_phrase_weights_crossfade_and_never_exceed_one():
    sr, n = 1000, 6000
    w = phrase_weights([(1.0, 2.0, "a"), (2.1, 3.0, "b")], ["a", "b"], n, sr, fade=0.15)
    assert w["a"][1500] == pytest.approx(1.0) and w["b"][1500] == 0
    assert w["b"][2500] == pytest.approx(1.0) and w["a"][2500] == 0
    total = w["a"] + w["b"]
    assert total.max() <= 1.0 + 1e-6
    assert w["a"][800] == 0 and 0 < w["a"][900] < 1  # 구절 0.15초 전부터 서서히
    assert w["a"][5000] == 0 and w["b"][5000] == 0  # 구절 밖은 무음


def test_phrase_weights_hand_over_inside_the_gap_without_singing_together():
    sr, n = 1000, 8000
    # 쉼 없이 붙은 경계(2.0초)와 0.1초 틈(3.0~3.1초), 넉넉한 틈(4.0~5.0초)
    asg = [(1.0, 2.0, "a"), (2.0, 3.0, "b"), (3.1, 4.0, "a"), (5.0, 6.0, "b")]
    w = phrase_weights(asg, ["a", "b"], n, sr, fade=0.15, xfade=0.04)
    both = (w["a"] > 0) & (w["b"] > 0)
    assert np.flatnonzero(both[1500:2500]).size <= 40  # 붙은 경계: 두 사람이 겹치는 건 0.04초뿐
    assert w["a"][2020] == 0 and w["b"][1980] == 0
    assert w["b"][2990] == pytest.approx(1.0) and w["a"][3110] == pytest.approx(1.0)  # 좁은 틈: 틈 안에서만 바뀐다
    assert np.flatnonzero(both[2900:3200]).size <= 100
    assert np.allclose((w["a"] + w["b"])[1990:2010], 1.0)  # 바뀌는 동안 소리가 꺼지지 않는다
    assert w["a"][4100] > 0 and w["b"][4900] > 0 and not both[4000:5000].any()  # 넉넉한 틈은 예전처럼 0.15초 페이드


def test_phrase_gains_match_original_level_per_line_within_limit():
    # 원래 보컬 0.3 크기. 엄마 변환은 절반(-6 dB), 첫째는 두 배(+6 dB), 아빠 둘째 줄은 거의 무음
    ref = np.concatenate([tone(2), tone(2), tone(2)])
    tracks = {"mom": tone(6, amp=0.15), "kid": tone(6, amp=0.6),
              "dad": np.concatenate([tone(4), tone(2, amp=0.001)])}
    gains = phrase_gains([(0, 2, "mom"), (2, 4, "kid"), (4, 6, "dad")], tracks, ref, SR)
    assert gains[0] == pytest.approx(2.0, rel=0.01)
    assert gains[1] == pytest.approx(0.5, rel=0.01)
    assert gains[2] == pytest.approx(10 ** (12 / 20), rel=0.01)  # 한도 +12 dB 에서 멈춘다


def test_phrase_weights_apply_gain_per_line_and_crossfade_it():
    sr, n = 1000, 6000
    w = phrase_weights([(1.0, 2.0, "a"), (2.0, 3.0, "a"), (3.5, 4.5, "b")], ["a", "b"], n, sr, fade=0.15,
                       gains=[2.0, 0.5, 3.0])
    assert w["a"][1500] == pytest.approx(2.0) and w["a"][2500] == pytest.approx(0.5)
    assert w["b"][4000] == pytest.approx(3.0)
    edge = w["a"][1850:2151]
    assert np.all(np.diff(edge) <= 1e-6) and edge[0] == pytest.approx(2.0) and edge[-1] == pytest.approx(0.5)  # 끊기지 않게


def test_compose_mixes_by_weight():
    n = 10
    tracks = {"a": np.ones((n, 2), np.float32), "b": np.full((n, 2), 2.0, np.float32)}
    weights = {"a": np.array([1] * 5 + [0] * 5, np.float32), "b": np.array([0] * 5 + [1] * 5, np.float32)}
    out = compose(tracks, weights)
    assert out[0, 0] == 1 and out[9, 1] == 2


def test_fit_length_pads_and_trims():
    x = np.ones((5, 2), np.float32)
    assert fit_length(x, 3).shape == (3, 2)
    padded = fit_length(x, 8)
    assert padded.shape == (8, 2) and padded[7, 0] == 0


LYRICS = """[verse]
서울에서 강릉까지 드라이브 시작해
엄마 말처럼 하늘이 파랗게 빛나네
“안 막히면 3시간쯤” 아빠가 웃어주네
[chorus]
우리 가족 최고 오늘이 제일 좋아"""


def test_layered_fraction_counts_windows_where_left_and_right_differ():
    sr = 8000
    t = np.arange(sr * 2) / sr
    one = np.sin(2 * np.pi * 220 * t).astype(np.float32)
    other = np.sin(2 * np.pi * 277 * t + 1.0).astype(np.float32)
    centered = np.stack([one, one], 1)  # 한 목소리가 가운데
    doubled = np.stack([one, other], 1)  # 좌우가 다른 소리 (두 겹)
    quiet = np.zeros((sr * 2, 2), np.float32)
    assert layered_fraction(centered, sr) == 0.0
    assert layered_fraction(doubled, sr) == 1.0
    assert layered_fraction(np.concatenate([centered, doubled, quiet]), sr) == pytest.approx(0.5)  # 무음은 세지 않는다
    assert layered_fraction(quiet, sr) == 0.0


def test_wordless_drops_hums_outside_the_lyrics_and_keeps_misheard_lines():
    # 2026-11-13 곡: 전주에 "헤에에"·"후후후" 흥얼거림 3구절, 가운데 "3시간쯤"은 받아 적기가 틀림
    phrases = [(0.0, 1.35), (2.79, 3.51), (3.9, 6.33), (9.1, 16.9), (17.3, 23.1), (25.8, 28.3), (28.9, 31.8),
               (32.2, 37.3), (45.8, 48.9), (49.2, 49.8), (52.0, 55.0)]
    texts = {0: "헤에에에", 2: "후후후후후후후후", 3: "서울에서 강남까지 드라이브 시작 맘 말처럼 하늘이",
             4: "넌 맘알처럼 하늘이 파랗게 빛나네", 5: "감막히면", 6: "삼성시가 즈음", 7: "아빠가 웃어줄래",
             8: "우리 가족 최고 오늘이", 10: "시청해 주셔서 감사합니다"}
    drop = wordless(phrases, texts, LYRICS)
    assert drop == [True, True, True, False, False, False, False, False, False, False, True]
    # 짧은 조각(1번)은 가까운 줄(2번)을 따라 빠지고, 9번은 8번을 따라 남는다. 끝의 엉뚱한 글(후주)은 뺀다


def test_wordless_keeps_everything_when_nothing_can_be_judged():
    phrases = [(0.0, 3.0), (4.0, 7.0)]
    assert wordless(phrases, {}, LYRICS) == [False, False]  # 받아 적지 않았다
    assert wordless(phrases, {0: "라라라", 1: ""}, LYRICS) == [False, False]  # 모두 빼게 되면 빼지 않는다
    assert wordless(phrases, {0: "랄랄라 랄라", 1: "엄마 말처럼 하늘이 파랗게"}, LYRICS) == [True, False]


def test_lyric_coverage_counts_lines_that_are_heard():
    heard = ["서울에서 강남까지 드라이브 시작", "넌 맘알처럼 하늘이 파랗게 빛나네", "우리 가족 최고 오늘이 제일 좋아"]
    assert lyric_coverage(heard, LYRICS) == pytest.approx(3 / 4)  # "안 막히면 …" 줄이 빠졌다
    assert lyric_coverage([], LYRICS) == 0.0
    assert lyric_coverage(heard, "[verse]\n") == 1.0
    assert candidate_score(0.24, 5 / 8) < candidate_score(0.50, 1.0)  # 덜 겹친 곡이 가사 3줄 빠져도 낫다


class _FakeAsr:
    def __init__(self, texts):
        self.texts, self.unloaded = list(texts), 0

    def transcribe(self, audio):
        return self.texts.pop(0)

    def unload(self):
        self.unloaded += 1


class _TwoSongs(Singer):
    """Demucs·Seed-VC 없이: 곡 파일을 그대로 보컬로 쓰고, 변환은 보컬을 복사한다."""

    def separate(self, song_paths, work):
        accomp = au_save(work / "silence.wav", np.zeros((SR * 12, 2), np.float32))
        return [(p, accomp) for p in song_paths]

    def convert(self, vocals, ref_path, shift, out_dir):
        return vocals


def au_save(path, audio):
    from voicelab import audio as au

    return au.save(path, audio, SR)


def test_sing_picks_the_less_layered_song_and_drops_the_hum(tmp_path):
    line = np.concatenate([tone(3), silence(1)])
    one = np.concatenate([line, line, line])  # 12초, 세 줄
    layered = au_save(tmp_path / "a.wav", np.stack([one, np.roll(one, 9)], 1))  # 좌우가 어긋난 보컬
    clean = au_save(tmp_path / "b.wav", np.stack([one, one], 1))
    asr = _FakeAsr(["엄마 말처럼 하늘이", "우리 가족 최고", "오늘이 제일 좋아",  # a
                    "후후후후", "엄마 말처럼 하늘이 파랗게", "우리 가족 최고 오늘이 제일 좋아"])  # b: 첫 줄은 흥얼거림
    singer = _TwoSongs(None, asr)

    r = singer.sing([str(layered), str(clean)], [SingerInput("mom", "ref.wav", None)], str(tmp_path / "out.wav"),
                    str(tmp_path), lyrics=LYRICS)

    assert r.picked == 1 and [c.layered for c in r.candidates] == [1.0, 0.0]
    assert np.allclose(r.dropped_phrases, [[0.0, 3.0]], atol=0.05)
    assert np.allclose(r.vocal_phrases, [[4.0, 7.0], [8.0, 11.0]], atol=0.05)
    assert r.singers[0].phrases == 2 and asr.unloaded == 1
    assert [a[2] for a in r.assignment] == ["mom", "mom"] and r.assignment[0][:2] == r.vocal_phrases[0]
    from voicelab import audio as au

    out = au.load(tmp_path / "out.wav")
    assert au.rms_db(out[:SR * 3]) < -60 and au.rms_db(out[SR * 4:SR * 7]) > -20  # 흥얼거림 자리는 조용하다
