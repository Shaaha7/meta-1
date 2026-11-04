import json, soundfile as sf, numpy as np
from kokoro_onnx import Kokoro
k = Kokoro("/tmp/claude-0/tts/kokoro-v1.0.int8.onnx", "/tmp/claude-0/tts/voices-v1.0.bin")
N=("af_heart","en-us",1.0); U=("bm_george","en-gb",1.02)
lines=[
 ("title",N,"This is the Ustad prototype: the real Android app, version two point seven, built on Meta's Wearables Device Access Toolkit."),
 ("consent",N,"On first start, Ustad explains exactly what it sends, and what never leaves the phone."),
 ("inspect",N,"The hub works with or without glasses. With glasses, the technician says the wake word, and Ustad takes the photo."),
 ("busy",N,"Faces and number plates are blurred on the phone first. Only then does Ustad look and reason."),
 ("result",N,"The result is simple: the machine, the most likely causes, and the hidden part in 3D. Tap explode, and the car comes apart."),
 ("details",N,"More details shows how Ustad decided: the reasoning graph, and which check to do first, by likelihood per minute."),
 ("coach",U,"Step one. Ignition off. Disconnect the negative terminal first."),
 ("coach2",N,"The repair coach speaks every step, with a safety stop, and listens for next, back, why and check."),
 ("lens",N,"On Ray-Ban Display glasses, the cause, the step and the part appear right in the lens."),
 ("lesson",N,"An expert's job becomes a lesson. Learners are coached step by step, checked by photo, and quizzed."),
 ("passport",N,"Every result goes into a Skill Passport, with badges at eighty and above."),
 ("dash",N,"Supervisors see skills, who needs help, and the most common faults on the team dashboard."),
 ("datalog",N,"And every request is logged on the phone: what left it, to whom, and how much."),
 ("end",N,"Ustad. One expert's knowledge, in every technician's glasses."),
]
out=[]
for i,(key,(v,l,sp),txt) in enumerate(lines):
    s,sr=k.create(txt,voice=v,speed=sp,lang=l); f=f"/tmp/claude-0/proto/v{i:02d}.wav"; sf.write(f,s,sr)
    hop=sr//30; e=[float(np.sqrt(np.mean(s[j:j+hop]**2))) for j in range(0,len(s),hop)]; m=max(e)
    out.append(dict(key=key,who='ustad' if v=="bm_george" else 'narr',text=txt,dur=round(len(s)/sr,2),file=f,env=[round(min(1,x/m*1.2),3) for x in e]))
    print(key, round(len(s)/sr,2))
json.dump(out,open('/tmp/claude-0/proto/voice.json','w'))
