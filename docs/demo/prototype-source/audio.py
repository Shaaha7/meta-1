import json, numpy as np, soundfile as sf
TL=json.load(open('timeline.json')); SR=44100; D=TL['total']; N=int(SR*D); t=np.arange(N)/SR
rng=np.random.default_rng(5)
from numpy.fft import rfft, irfft, rfftfreq
def lp(x,fc): X=rfft(x); f=rfftfreq(len(x),1/SR); X*=1/np.sqrt(1+(f/fc)**4); return irfft(X,len(x))
note=lambda m:440*2**((m-69)/12)
music=np.zeros((N,2))
chords=[[57,64,69,72],[53,60,65,69],[48,55,64,67],[55,62,67,71]]; seglen=5.2; s0=0; k=0
L=np.zeros(N); R=np.zeros(N)
while s0<D:
    n=int((seglen+2)*SR); tt=np.arange(n)/SR; e=np.minimum(1,tt/1.8)*np.minimum(1,np.maximum(0,(seglen+2-tt))/2)
    i=int(s0*SR); j=min(N,i+n)
    for m in chords[k%4]:
        for det,arr in ((-.06,L),(.06,R)):
            f=note(m)*2**(det/12); sig=(np.sin(2*np.pi*f*tt)+.35*np.sin(4*np.pi*f*tt+.3))*e*.03
            arr[i:j]+=sig[:j-i]
    s0+=seglen; k+=1
music[:,0]=lp(L,1400); music[:,1]=lp(R,1400)
# soft pulse
beat=60/84; b=10.0
while b<D-6:
    n=int(.3*SR); tt=np.arange(n)/SR; s=np.sin(2*np.pi*np.cumsum(50+60*np.exp(-tt*30))/SR)*np.exp(-tt*9)*.12
    i=int(b*SR); music[i:i+n,0]+=s[:N-i]; music[i:i+n,1]+=s[:N-i]; b+=beat
shape=np.interp(t,[0,1.5,D-3,D],[0,1,1,0]); music*=shape[:,None]
fx=np.zeros((N,2))
def add(sig,st,g=1.0):
    i=int(st*SR); sig=sig[:max(0,N-i)]; fx[i:i+len(sig),0]+=sig*g; fx[i:i+len(sig),1]+=sig*g
def click(f=1800):
    n=int(.08*SR); tt=np.arange(n)/SR; return (np.sin(2*np.pi*f*tt)+.4*np.sin(2*np.pi*f*1.5*tt))*np.exp(-tt*60)
def swoosh():
    n=int(.6*SR); x=lp(rng.standard_normal(n),2500); return x*np.sin(np.pi*np.arange(n)/n)**2*.5
S={s['key']:s for s in TL['scenes']}
for s in TL['scenes'][1:]: add(swoosh(),s['t0']-.3,.18)
add(click(1700),S['inspect']['t1']-1.3,.3); add(click(1900),S['result']['t0']+4.6,.3)
voice=np.zeros(N); duck=np.ones(N)
for l in TL['lines']:
    v,sr=sf.read(l['file']); x=np.interp(np.arange(int(len(v)*SR/sr))*sr/SR,np.arange(len(v)),v); x=x/np.max(np.abs(x))*.9
    if l['who']=='ustad':
        y=x.copy(); d=int(.007*SR); y[d:]+=.28*x[:-d]; x=y/np.max(np.abs(y))*.9
    i=int(l['t']*SR); e=min(N,i+len(x)); voice[i:e]+=x[:e-i]; duck[max(0,i-2000):e]=.4
w=int(.15*SR); c=np.cumsum(np.concatenate([[0],duck])); duck=np.concatenate([(c[w:]-c[:-w])/w, np.full(w-1, duck[-1])])[:N]
out=music*.7*duck[:,None]+fx+voice[:,None]
out=out/np.max(np.abs(out))*.89; sf.write('sound.wav',out,SR); print('ok',D)
