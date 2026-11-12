"""GPU·모델 없이 도는 로직 시험 (설계 §7.5)."""

import numpy as np
import pytest

from voicelab.analyze import REF_MAX_SEC, Segment, match_speaker, pick_moment, pick_reference
from voicelab.audio import voiced_segments
from voicelab.sing import assign_phrases, compose, fit_length, octave_shift, phrase_weights

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
    phrases = [(0, 1), (2, 3), (4, 5)]
    assert assign_phrases(phrases, ["mom", "dad"]) == [(0, 1, "mom"), (2, 3, "dad"), (4, 5, "mom")]
    assert assign_phrases(phrases, []) == []


def test_phrase_weights_crossfade_and_never_exceed_one():
    sr, n = 1000, 6000
    w = phrase_weights([(1.0, 2.0, "a"), (2.1, 3.0, "b")], ["a", "b"], n, sr, fade=0.15)
    assert w["a"][1500] == pytest.approx(1.0) and w["b"][1500] == 0
    assert w["b"][2500] == pytest.approx(1.0) and w["a"][2500] == 0
    total = w["a"] + w["b"]
    assert total.max() <= 1.0 + 1e-6
    assert w["a"][800] == 0 and 0 < w["a"][900] < 1  # 구절 0.15초 전부터 서서히
    assert w["a"][5000] == 0 and w["b"][5000] == 0  # 구절 밖은 무음


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
