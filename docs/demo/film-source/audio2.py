import json, numpy as np, soundfile as sf
SR=44100; D=67.5; N=int(SR*D); t=np.arange(N)/SR
mix=np.zeros((N,2)); rng=np.random.default_rng(3)
def onepole(x,a):  # simple low-pass
    y=np.empty_like(x); s=0.0
    for i in range(len(x)): s+=a*(x[i]-s); y[i]=s
    return y
def lp(x,fc):
    from numpy.fft import rfft, irfft, rfftfreq
    X=rfft(x); f=rfftfreq(len(x),1/SR); X*=1/np.sqrt(1+(f/fc)**4); return irfft(X,len(x))
def add(sig,start,gain=1.0,pan=0.0):
    i=int(start*SR); sig=sig[:max(0,N-i)]
    l=np.cos((pan+1)*np.pi/4); r=np.sin((pan+1)*np.pi/4)
    mix[i:i+len(sig),0]+=sig*gain*l*1.41; mix[i:i+len(sig),1]+=sig*gain*r*1.41
def env(n,a,r):
    e=np.ones(n); a=int(a*SR); r=int(r*SR)
    if a: e[:a]=np.linspace(0,1,a)
    if r: e[-r:]*=np.linspace(1,0,r)
    return e
note=lambda m:440*2**((m-69)/12)
# --- pad: Am  F  C  G  (each 4.2 s), detuned saws, low-passed
chords=[[57,60,64,69],[53,57,60,65],[48,55,60,64],[55,59,62,67]]
seglen=4.2; pad=np.zeros(N)
padR=np.zeros(N)
k=0; s0=0.0
while s0<D:
    c=chords[k%4]; n=int(seglen*SR)+int(1.5*SR); tt=np.arange(n)/SR
    for m in c:
        for det,arr in ((-.07,pad),(.07,padR)):
            f=note(m)*2**(det/12)
            saw=2*((tt*f)%1)-1
            seg=saw*env(n,1.4,1.6)*.05
            i=int(s0*SR); e=min(N,i+n); arr[i:e]+=seg[:e-i]
    s0+=seglen; k+=1
pad=lp(pad,900); padR=lp(padR,900)
# overall pad level shape: soft intro, lift in reasoning/exploded, outro
shape=np.interp(t,[0,2,6,22,31,40,51.6,60,64,67.5],[0,.7,.55,.65,1,.7,.85,1,.8,0])
mix[:,0]+=pad*shape; mix[:,1]+=padR*shape
# --- sub pulse (heartbeat kicks) 92 bpm from 6 s
beat=60/92
def kick():
    n=int(.45*SR); tt=np.arange(n)/SR; f=48+90*np.exp(-tt*28)
    return np.sin(2*np.pi*np.cumsum(f)/SR)*np.exp(-tt*7)
kk=kick(); b=6.0
while b<60:
    g=.32 if b<31 else (.45 if b<40 else .3)
    add(kk,b,g); b+=beat
# --- arp (reasoning + exploded)
arp=[69,72,76,81,76,72]; a=22.6; i=0
while a<40:
    n=int(.22*SR); tt=np.arange(n)/SR; f=note(arp[i%6]+ (0 if a<31 else -4 if (int((a-31)/4.2)%2) else 0))
    s=(np.sin(2*np.pi*f*tt)+.3*np.sin(4*np.pi*f*tt))*np.exp(-tt*16)
    add(s,a,.07,pan=.5*np.sin(i*.9)); a+=beat/2; i+=1
# --- whooshes
def whoosh(d=1.2,up=True):
    n=int(d*SR); x=rng.standard_normal(n); tt=np.linspace(0,1,n)
    out=np.zeros(n); blk=1024
    for j in range(0,n,blk):
        fc=300+ (5000*tt[j]**2 if up else 5000*(1-tt[j])**2); a=1-np.exp(-2*np.pi*fc/SR)
        out[j:j+blk]=onepole(x[j:j+blk],a)
    return out*np.sin(np.pi*tt)**2
for s in [5.0,13.0,30.4,39.2,50.8,59.4]: add(whoosh(1.2),s,.28)
# --- impacts
def boom():
    n=int(2.5*SR); tt=np.arange(n)/SR; f=38+60*np.exp(-tt*9)
    return (np.sin(2*np.pi*np.cumsum(f)/SR)*np.exp(-tt*2.2)+.25*lp(rng.standard_normal(n),400)*np.exp(-tt*5))
for s,g in [(.8,.75),(31.75,.8),(60.4,.8)]: add(boom(),s,g)
# --- shimmer riser into title / end
def riser(d):
    n=int(d*SR); tt=np.arange(n)/SR; f=600+2400*(tt/d)**2
    return np.sin(2*np.pi*np.cumsum(f)/SR)*(tt/d)**2*.5
add(riser(1.6),0.2,.05); add(riser(2.0),58.4,.06)

# --- footsteps, bonnet latch, gas struts
def step():
    n=int(.12*SR); tt=np.arange(n)/SR; return (lp(rng.standard_normal(n),900)*np.exp(-tt*45)+.5*np.sin(2*np.pi*70*tt)*np.exp(-tt*30))
for i in range(4): add(step(),6.05+i*.34,.5,pan=.2)
def clunk():
    n=int(.35*SR); tt=np.arange(n)/SR; return (np.sin(2*np.pi*180*tt)*np.exp(-tt*25)+.6*lp(rng.standard_normal(n),2500)*np.exp(-tt*60))
add(clunk(),7.98,.55); add(clunk()*.6,9.6,.4)
n=int(.9*SR); tt=np.arange(n)/SR; hiss=lp(rng.standard_normal(n),3500)*np.sin(np.pi*tt/.9)**2
add(hiss,8.7,.12)
# --- UI blips
def blip(f=1800,d=.09):
    n=int(d*SR); tt=np.arange(n)/SR
    return (np.sin(2*np.pi*f*tt)+.4*np.sin(2*np.pi*f*1.5*tt))*np.exp(-tt*45)
for s,f in [(9.95,1600),(15.3,2000),(17.2,1500),(17.65,1700),(23.4,1900),(24.2,2100),(25.0,2300),(35.6,1400),(36.3,1500),(37.0,1600),(37.7,1800),
            (40.6,1700),(45.1,2200),(46.0,1700),(52.8,1500),(54.4,1700),(56.0,1900)]:
    add(blip(f),s,.22,pan=rng.uniform(-.4,.4)); add(blip(f*1.5),s+.07,.12)
def chime():  # success
    out=np.zeros(int(.9*SR))
    for j,m in enumerate([76,81,88]):
        n=int(.7*SR); tt=np.arange(n)/SR; s=np.sin(2*np.pi*note(m)*tt)*np.exp(-tt*5); i=int(j*.09*SR); out[i:i+n]+=s
    return out
add(chime(),49.6,.25); add(chime(),57.4,.28)
# --- shutter
def shutter():
    n=int(.16*SR); tt=np.arange(n)/SR; x=rng.standard_normal(n)
    return x*(np.exp(-tt*90)+.6*np.exp(-np.maximum(0,tt-.06)*120)*(tt>.06))
for s in [14.33,49.08]: add(shutter(),s,.35)
# --- scan sweep
n=int(2.4*SR); tt=np.arange(n)/SR; f=300+900*tt/2.4
add(np.sin(2*np.pi*np.cumsum(f)/SR)*np.sin(np.pi*tt/2.4)*.5*(.6+.4*np.sin(2*np.pi*12*tt)),14.8,.06)

music=mix.copy(); mix[:]=0
# --- voices (with music ducking)
L=json.load(open('lines.json')); duck=np.ones(N)
from math import gcd
for l in L:
    v,sr=sf.read(l['file'])
    # resample 24k -> 44.1k
    x=np.interp(np.arange(int(len(v)*SR/sr))*sr/SR, np.arange(len(v)), v)
    x=x/np.max(np.abs(x))*.9
    if l['who']=='ustad':  # light "AI" colour: short comb chorus + presence
        dl=int(.007*SR); y=x.copy(); y[dl:]+=.28*x[:-dl]
        dl2=int(.013*SR); y[dl2:]+=.15*x[:-dl2]; x=y/np.max(np.abs(y))*.9
        add(x,l['t'],.95,pan=0)
    else:
        rv=np.zeros(len(x)+int(.3*SR)); rv[:len(x)]+=x
        for d,g in [(.031,.18),(.047,.12),(.071,.08)]: i=int(d*SR); rv[i:i+len(x)]+=g*x
        add(rv,l['t'],.85,pan=-.08)
    i=int(l['t']*SR); e=min(N,i+len(x)); duck[i:e]=.38
duck=onepole(duck,1-np.exp(-1/(.12*SR)))
# duck everything except voices: simplest — apply to the music bus built before voices
# (rebuild by subtracting voices is overkill; we instead scale the pre-voice mix)

out=music*.55*duck[:,None]+mix
out=out/np.max(np.abs(out))*.89
sf.write('/tmp/claude-0/film/sound.wav',out,SR)
print('ok',out.shape)
