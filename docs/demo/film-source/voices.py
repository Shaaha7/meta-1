import json, soundfile as sf
from kokoro_onnx import Kokoro
k = Kokoro("kokoro-v1.0.int8.onnx", "voices-v1.0.bin")
T, U = ("am_michael", "en-us"), ("bm_george", "en-gb")
lines = [
 ("tech",  7.4, "Ustad, what's wrong with this car? It won't start."),
 ("ustad", 11.2, "Hold still. I'm taking a look."),
 ("ustad", 16.0, "Faces and number plates are blurred on your phone. Now, reading the engine bay."),
 ("ustad", 22.6, "I can see white corrosion on the negative battery terminal, and the battery warning light is on."),
 ("ustad", 28.4, "Most likely: corroded twelve volt terminals. Seventy one percent sure."),
 ("ustad", 32.2, "Here is the hidden part, and why it fails."),
 ("ustad", 40.4, "Step one. Ignition off. Disconnect the negative terminal first."),
 ("tech",  45.2, "Next."),
 ("ustad", 46.0, "Step two. Clean both terminals, then show me."),
 ("ustad", 49.8, "Perfect. The terminals are clean. Well done."),
 ("ustad", 52.6, "Every job an expert records becomes a lesson. Then Ustad guides every new learner, step by step."),
 ("ustad", 61.0, "Ustad. The master technician, in your glasses."),
]
out = []
for i,(who,t,txt) in enumerate(lines):
    v,l = T if who=="tech" else U
    s, sr = k.create(txt, voice=v, speed=1.0 if who=="tech" else 1.02, lang=l)
    f = f"/tmp/claude-0/film/line{i:02d}.wav"; sf.write(f, s, sr)
    out.append(dict(who=who, t=t, text=txt, dur=round(len(s)/sr,2), file=f))
    print(i, who, t, round(len(s)/sr,2), round(t+len(s)/sr,2))
json.dump(out, open("/tmp/claude-0/film/lines.json","w"), indent=1)
