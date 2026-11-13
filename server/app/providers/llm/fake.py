from app.providers.llm.base import TIME_OF_DAY_KO, LyricsInput


class FakeLlm:
    """주행 정보로 §7.2 구조의 가사를 만드는 결정적 구현 (키 없이 전체 흐름 확인용)."""

    name = "fake"
    external = False

    async def write_lyrics(self, inp: LyricsInput) -> str:
        origin = inp.origin_name or "우리 동네"
        dest = inp.destination_name or "새로운 곳"
        middle = [p for p in inp.place_names if p not in (origin, dest)]
        place = middle[0] if middle else dest
        nick = inp.family_nicknames[0] if inp.family_nicknames else "우리 가족"
        weather = inp.weather.description if inp.weather else "좋은 날씨"
        # 대화가 있으면 첫 말을 그대로 넣는다 (§7.5)
        said = inp.conversation[0].split(": ", 1)[-1] if inp.conversation else f"창밖엔 {weather}"
        return "\n".join([
            "[verse]",
            f"{TIME_OF_DAY_KO[inp.time_of_day]} 햇살 따라",
            f"{origin}에서 출발",
            f"{place} 지나서",
            said,
            "[chorus]",
            f"{nick} 손을 잡고",
            "어디든 좋아요",
            "신나는 드라이브",
            f"{dest}까지 가요",
        ])
