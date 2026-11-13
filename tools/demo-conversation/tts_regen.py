"""발음이 뭉개진 줄만 후보 3개씩 다시 만든다 (CosyVoice venv). python regen.py <dir> 1,8,9,17,22,23"""
import json, sys
from pathlib import Path
import torch, torchaudio
sys.path.append("third_party/Matcha-TTS")
from cosyvoice.cli.cosyvoice import AutoModel
d = Path(sys.argv[1]); want = set(map(int, sys.argv[2].split(",")))
STYLE = {"아빠": "请用温和、开朗的爸爸语气说这句话。", "엄마": "请用温柔、开心的妈妈语气说这句话。",
         "첫째": "请用天真活泼、兴奋的小孩语气说这句话。"}
m = AutoModel(model_dir="pretrained_models/Fun-CosyVoice3-0.5B")
for x in json.loads((d / "conversation.json").read_text(encoding="utf-8")):
    if x["i"] not in want:
        continue
    for k in range(3):
        gen = m.inference_instruct2(x["text"], f"You are a helpful assistant. {STYLE[x['who']]}<|endofprompt|>",
                                    str(d / "prompts" / f"{x['who']}.wav"), stream=False)
        s = torch.cat([r["tts_speech"] for r in gen], dim=1)
        torchaudio.save(str(d / "lines" / f"{x['i']:02d}_{x['who']}_c{k}.wav"), s, m.sample_rate)
    print(x["i"], "done", flush=True)
