# 시연 영상 AI 장면 프롬프트 (v1, 2026-11-11)

> `docs/video-storyboard.md`의 AI 장면을 만드는 프롬프트 모음. 차량은 **현대 더 뉴 그랜저**(Pleos Connect가 처음 들어간 차, 2026-05-14 출시)로 확정했다.
> 앱 화면은 AI로 만들지 않는다. 화면 자리는 비워 두고 에뮬레이터 녹화를 편집에서 합성한다 (가짜 화면 금지).

## 0. 공통 설정 (Higgsfield)
- 모델: **Seedance 2.0** (mode `std`, 1080p), 화면비 **9:16** (본편용은 16:9로 한 번 더), 소리 생성 **끄기** (노래·대화는 앱이 만든 것을 편집에서 입힌다).
- 길이: 장면마다 적힌 초. 같은 프롬프트로 2~3개 뽑아 고른다.
- 일관성: 먼저 아래 1번 참고 이미지 2장(가족, 차)을 만들고, 모든 영상에 **이미지 참고(image reference)**로 넣는다.
- 화면 구도 (`docs/video-frame-guide.png`): 숏폼은 위 18%에 큰 자막, 아래 35%에 가사 자막이 올라간다. **얼굴·차는 위에서 20~55% 띠**에 오게. 그 아래는 자막이 덮으니 단순한 배경으로.
- 지킬 것: 운전자는 주행 중 화면을 보지도 만지지도 않는다. 번호판·로고·글자가 보이지 않게. 실제 사람 얼굴을 쓰지 않는다 (AI 인물).
- 그랜저가 엉뚱하게 나오면: 프롬프트에서 `Hyundai Grandeur`를 빼고 디자인 설명만 남긴다. 엠블럼이 이상하게 그려지면 그 컷은 버린다.
- 가족 구성은 노래를 부른 가족과 맞춘다: 지금 데모 노래는 **엄마·아빠·첫째(3명)**. 4명이면 프롬프트의 가족 설명에 `and her 5-year-old little brother`를 더한다.

공통 문구 (프롬프트 끝에 붙임):
```
cinematic, natural candid moment, realistic skin and fabric, soft natural light, shallow depth of field, 35mm film look, vertical 9:16 framing with the subjects in the middle third, no text, no subtitles, no visible logos or badges, no license plates
```

## 1. 참고 이미지 (먼저 만들기, 이미지 모델)
**R1. 가족** (정면 3명, 상반신)
```
A warm Korean family of three for a road-trip commercial: a father in his late 30s with short black hair and a navy knit sweater, a mother in her late 30s with shoulder-length dark brown hair and a cream cardigan, and their 9-year-old daughter with a high ponytail and a yellow hoodie. They stand side by side smiling naturally, plain light gray studio background, even soft lighting, photorealistic, full color, front view, upper body
```
**R2. 차** (그랜저 외관 3/4 앞모습)
```
A 2026 Hyundai Grandeur full-size luxury sedan in deep blue-gray metallic paint, front three-quarter view, slim seamless full-width horizontal LED light bar across the front, long sleek body with a smooth sloping roofline, parked on an empty coastal road in soft morning light, photorealistic automotive photography, no license plate, no visible badge
```

## 2. 숏폼 장면 (우선)
| 번호 | 숏폼 위치 | 길이 | 내용 |
|---|---|---|---|
| S1 | 0~9초 배경 (대화 말풍선 뒤) | 8초 | 고속도로, 뒷좌석 첫째가 떠들고 엄마가 돌아보며 웃음, 아빠는 앞만 봄 |
| S2 | 18~21초 (도착) | 5초 | 경포해변 주차장에 그랜저가 들어와 멈춤, 노을 |
| S3 | 21~24초 (P단) | 4초 | 운전대 뒤 칼럼식 변속 레버를 P로, 손 클로즈업 |
| S4 | 24~40초 배경 (가사 자막 뒤) | 8초 | 실내 조명이 노래 색으로 바뀌고 가족이 서로 보며 웃음 (고정 카메라) |
| S5 | 40~46초 배경 (엔딩) | 6초 | 해 질 녘, 바다 쪽으로 걸어가는 가족 뒷모습과 주차된 그랜저 |

**S1. 차 안 대화** (참고: R1, R2)
```
Inside a Hyundai Grandeur sedan driving on a Korean highway through autumn mountains on a sunny morning. Camera in the back seat area looking forward diagonally. The 9-year-old daughter in the back seat talks excitedly and gestures with her hands, the mother in the front passenger seat turns around and laughs with her, the father keeps both hands on the steering wheel and his eyes on the road. Wide horizontal dashboard with a large curved center display that is turned off, warm sunlight flickering through the windows, gentle handheld motion
```
**S2. 경포해변 도착** (참고: R2)
```
Golden hour at Gyeongpo beach in Gangneung, Korea. A deep blue-gray Hyundai Grandeur sedan slowly drives into an almost empty seaside parking lot and comes to a gentle stop facing the ocean, its slim full-width LED light bar glowing. Calm sea, pine trees along the shore, warm orange sky. Slow camera push-in from the side, no license plate
```
**S3. P단** (참고: R2)
```
Close-up inside a modern luxury sedan: the driver's hand moves a slim column-mounted electronic gear selector stalk behind the steering wheel into the Park position, then rests on the wheel. Soft warm sunset light, elegant dark interior with fine stitching, shallow depth of field, static camera
```
**S4. 노래가 시작되는 실내** (참고: R1, R2) — **고정 카메라**
```
Parked by the sea at sunset, inside a Hyundai Grandeur sedan, camera fixed on a tripod in the back seat looking toward the front. The thin ambient light strips along the dashboard and doors slowly change from soft white to cyan and then warm pink as music starts playing. The father and mother turn to look at each other and smile, the daughter in the back seat leans forward between the front seats, sways to the music and sings along. The large curved center display is turned off. Static camera, no camera movement
```
**S5. 엔딩** (참고: R1, R2)
```
Dusk at Gyeongpo beach. A family of three walks hand in hand from a parked deep blue-gray Hyundai Grandeur sedan toward the sea, seen from behind, the daughter in the middle skipping. Soft pink and purple sky, gentle waves, the car's slim LED tail light bar glowing softly in the foreground. Slow camera pull back, faces not visible
```

## 3. 본편 장면 (16:9로도 뽑기)
**M1. 출발 (본편 1, 10초)**
```
Saturday morning in an apartment parking lot in Seoul. The family of three loads travel bags and a cooler into the trunk of a deep blue-gray Hyundai Grandeur sedan, the daughter carries a small backpack and hops into the back seat, the mother laughs, the father closes the trunk. Fresh morning light, birds, autumn trees, slow handheld tracking shot, no license plate
```
**M2. 대화 담기 켜기 (본편 2, 6초) — 고정 카메라, 화면 합성용**
```
Inside a parked Hyundai Grandeur sedan before departure, camera fixed on a tripod behind the front seats. The mother in the passenger seat reaches toward the large curved center display and taps it once, the father fastens his seatbelt. The center display shows a plain dark navy blue screen with no content. Static camera, no camera movement, soft morning light
```
→ 편집에서 화면 자리(네 모서리)에 실제 앱 녹화(탑승자 화면 → 대화 담기 켜기)를 합성한다. 그래서 카메라가 움직이면 안 된다.

**M3. 고속도로 (본편 3)**: S1을 16:9로. 바깥 컷이 하나 더 필요하면:
```
Aerial drone shot following a deep blue-gray Hyundai Grandeur sedan on a winding Korean highway through colorful autumn mountains toward the east coast, morning sun, smooth tracking
```
**M5. 도착 (본편 5)**: S2·S3·S4를 16:9로.

**M6. 그날 밤 (본편 6, 6초)**
```
Night in a cozy wooden pension room near the beach. The 9-year-old daughter sits on the bed hugging her knees, holding a tablet whose screen faces away from the camera, listening with a big smile, the mother sits next to her and leans in. Warm lamp light, gentle slow push-in
```

## 4. 받은 영상 넘기기
- 파일 이름을 장면 번호로 (`S1.mp4`, `S2_b.mp4` 처럼) 저장해 `C:\Drive Song\video-ai\` 폴더에 넣어 주면 편집 스크립트(`tools/promo-video/`)로 v1을 만든다. 이 폴더는 git에 올리지 않는다.
- 마지막 표기: "일부 장면은 AI로 생성했습니다" (콘티 §0).
