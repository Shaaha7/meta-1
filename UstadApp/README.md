# Ustad: See inside any machine

Android prototype for Meta Wearables Studio UAE. Built on Meta's CameraAccess sample (Wearables Device Access Toolkit 1.0.1).

**Inspect:** glasses photo → faces blurred on device → AI reports what it sees → for cars and split ACs, Ustad's knowledge-base model ranks the hidden failing parts (other machines: AI diagnosis) → 3D exploded view highlights the part → technician proves it with a quick test → repair coach speaks the fix (English / Hindi / Arabic).

**Teach → Learn:** expert records a job → ~10 face-blurred key frames → AI drafts a lesson (steps, why, safety, checkpoints, quiz, 3D part per step) → expert reviews and publishes → learner is guided hands-free, shows checkpoint photos, takes the quiz → Skill Passport score.

## Everything in the app

| Feature | What it does |
|---|---|
| 🔍 **Inspect + X-ray reveal** | Photo of a machine → defect → hidden failing part in a 3D exploded view (car, split AC, battery pack, or an AI-drawn layout for anything else) |
| 🧠 **Brain** | Knowledge-base diagnosis for cars and ACs: Bayesian ranking, next best question, learns from confirmed fixes, explains itself, reasoning graph |
| 🎧 **Listen** | 6 s of the running machine → loudness, pitch, spectrum on the phone; the AI marks sound symptoms (rattle, hiss, squeal, slow crank, rough idle) for the brain |
| 🧪 **Prove it** | The 10-second test, judged from a photo of the result (confirmed / not confirmed / unclear) |
| 🛡 **Safety Stop** | Fixed rules on AI repair steps and lesson drafts (high voltage, refrigerant, gas, airbags, mains panel) |
| 🔋 **Battery module** | Lithium packs: OK / WATCH / REPLACE / ISOLATE NOW, remaining cycles, the failing layer (anode, cathode, separator, electrolyte) in a 3D pack |
| 🎓 **Teach → Learn** | Expert video (+ their voice) → lesson with the expert's own words per step → learner guided hands-free → quiz → Skill Passport |
| 📊 **Team dashboard** | Technicians, skill badges (score 80+), "needs help", most common faults, recent jobs; records shared between phones, dashboard shared as one HTML file |
| 📞 **Live expert** | Job summary to a senior on WhatsApp, then a glasses video call ("Hey Meta, video call … on WhatsApp") |
| 🕶 **Ray-Ban Display** | With display glasses, the cause / step and the exploded view appear in the lens (mwdat-display) |
| 🗣 **Voice** | ElevenLabs or phone voice; Talk, Hands-free, your own wake word; commands in 6 languages; Ask Ustad |
| 🎯 **Accuracy test** | Photos with a known cause → top-1 / top-3 accuracy and a shareable report (brain learning switched off, so the score is fair) |
| ⏱ **Check in this order** | The brain orders checks by likelihood per minute of testing, the quickest likely causes first |
| 🔒 **Privacy** | Faces and number plates blurred on the phone; photos and sound only on command; optional backend holds all keys |

## Ustad's brain

Machines in the knowledge base (cars, split ACs) are diagnosed by Ustad's own model, not by the AI. The AI only reports what it can see.

- **Knowledge graph:** symptom → failure mode → internal part, with how likely each symptom is under each failure, a prove-it test, fix steps and sources (`assets/ustad/kb/*.json`). The **reasoning graph** view draws the part that matters for the current job.
- **Ranking:** Bayesian. P(failure | evidence) ∝ prior × Π P(symptom | failure).
- **Next best question:** the unanswered symptom with the highest information gain.
- **Explains itself:** for the leading cause, how strongly each piece of evidence supports it or argues against it.
- **Learns from confirmed fixes:** each fix a technician confirms with the test is stored as a case. Following decision-theoretic troubleshooting (Heckerman, Breese & Rommelse, Microsoft Research), the knowledge base counts as 20 cases of prior knowledge (Dirichlet/Beta pseudo-counts) and every confirmed case updates the priors and symptom likelihoods. A fix on the **same make and model counts 4×**, so a model's recurring fault rises to #1. Learning is recomputed from the case list, so it can be audited, undone or shared.
- **Shared knowledge:** *Share knowledge* exports what a phone learned; *Import* merges another technician's file.
- **Prove it with a photo:** after the test, show Ustad the result (📸 or "*word*, check"). The AI only judges the photo: *confirmed* (the brain learns), *not confirmed* (next cause) or *unclear* (what to show instead).
- **Safety Stop:** AI-written repair steps (machines outside the knowledge base) and AI lesson drafts are checked by fixed rules. If the first step doesn't make the machine safe, an isolation step is added. High-voltage EV, refrigerant, gas, airbag and mains-panel work stops the coaching with "call a certified professional". Running engines, fuel, capacitors, jacks and hot parts get warnings.
- **Machine history and job reports:** every confirmed visit is saved per machine and can be shared as a job report.

## Battery module

Photograph a lithium pack (e-bike, scooter, power bank, tool, storage) with Inspect, or open **🔋 Battery check** in the hub. Ustad reads the label and looks for swelling, leaks, burn marks and corrosion. You add what you measured (capacity, cycles, resistance rise, temperature). The verdict is rule-based, with no AI involved:

- **ISOLATE NOW:** swelling, leak, burn marks, a solvent smell, or 60 °C or more.
- **REPLACE:** capacity below **70%**, the end-of-life line used in NASA Ames' Li-ion aging tests (30% fade), or internal resistance doubled.
- **WATCH:** below 80%, resistance +50%, 45 °C or more, or corroded terminals.
- **Remaining cycles:** uses this pack's own fade rate when the cycle count is known. Otherwise it uses about 0.16% per cycle, roughly what NASA's test cells showed under accelerated room-temperature cycling, so the estimate is cautious.
- **Failing layer:** separator (heat, short circuit), electrolyte (gas, swelling), anode (SEI growth, rising resistance) or cathode (heat damage). It glows red in the 3D pack.

This is a calibrated rule model, not a machine-learning model trained on the NASA dataset. Training on the dataset is a possible next step.

## Ray-Ban Display

With **Ray-Ban Display** glasses, Ustad requests the display when the glasses session starts (`mwdat-display`). The lens shows the leading cause or the current step, the exploded view with the failing part in red, and one line of instruction. Glasses without a display simply decline and nothing changes. The voice bar shows **🕶 Lens** while it's active.

## Backend (recommended for any APK you share)

`../backend` is a tiny Node.js server that holds the Gemini and ElevenLabs keys. Set `USTAD_BACKEND_URL` and `USTAD_APP_TOKEN` in `local.properties`, and the app sends everything through it and contains no API keys. See [backend/README.md](../backend/README.md).

## Talk to Ustad (voice)

- **Ustad speaks** with a natural **ElevenLabs** voice: Flash v2.5 (low latency, 32 languages) for English, Hindi, Arabic and Tagalog, and Eleven v3 for Urdu and Malayalam. Each line is cached, so repeats cost no credits. Tap **🗣 Natural / 🤖 Phone** to switch to the phone's built-in voice. Ustad falls back to it automatically when there's no key or no network.
- **Ustad listens:** tap **🎙 Talk**, or switch on **Hands-free** so the mic opens every time Ustad finishes speaking. It never listens while it talks.
- **Commands** in English, Hindi, Arabic, Urdu, Malayalam and Tagalog (mixing is fine): next, back, repeat, why, yes, no, not sure, confirmed, not this, stop, and quiz answers by number.
- **Your own wake word** (set it in the hub; default "Hey Mentor"): switch on *Wake word* and Ustad listens all the time but only acts on "*word*, …". On the glasses screen: "*word*, inspect" takes the photo, "*word*, start lesson" / "*word*, stop lesson" records the expert and opens the lesson builder. Saying just the word gets "Yes?".
- **Ask Ustad:** any longer sentence is a question. It's answered in a few spoken words from the current job (step, why, cause chain, safety, sources). Anything unsafe gets "Stop, call a professional".

```properties
ELEVENLABS_API_KEY=your_key
# optional: ELEVENLABS_VOICE_ID=... (any voice from your ElevenLabs library)
```

Speech recognition uses Android's recognizer and stays on the phone when that language's offline pack is installed.

## Setup

1. Open this folder in Android Studio and let Gradle sync.
2. Get a free Gemini API key at https://aistudio.google.com/apikey.
3. Add to `local.properties` (it is gitignored, so never commit the key):

   ```properties
   GEMINI_API_KEY=your_key_here
   # optional: override the model
   # GEMINI_MODEL=gemini-flash-latest
   ```

   Offline / no key: run Meta's Llama on the laptop and forward it to the phone over USB:

   ```
   ollama pull llama3.2-vision
   adb reverse tcp:11434 tcp:11434
   ```

4. Run on an Android phone (Android 12+). Developer Mode does not require Meta credentials.

## Run with no glasses: the USTAD hub

Tap the **USTAD** button on the left edge of any screen. The phone camera and gallery stand in for the glasses:

- **Inspect a machine:** take or pick a photo of a car or engine bay.
- **Teach:** record a video of the expert doing the job (or pick one), add a one-line note, **Build lesson**, then review and **Approve & publish**.
- **Lessons:** two built-in car lessons (12V battery terminals, tire check) work with **no AI at all**, so they're a safe demo path. Checkpoint photos use the AI; if it's unreachable, the trainer can confirm instead.
- **Skill Passport:** score = 60% quiz + 40% checkpoints passed first time.
- **Brain & machine history:** confirmed fixes learned, recent jobs (shareable reports), share or import knowledge.
- **Languages:** English, Hindi, Arabic, Urdu, Malayalam, Tagalog. Phone voices for some may need installing; ElevenLabs covers more.

## Run with Mock Device Kit (simulated glasses)

1. Launch Ustad, tap the **ladybug** (debug) icon, then **Enable MockDeviceKit**.
2. **Pair Ray-Ban Meta**, then turn on **Power** and **Donned**.
3. Camera source: **phone back camera** (point it at the AC) or a **video file** (H.265).
4. **Start session** → **Preview** → tap **capture**. Ustad opens with the diagnosis.
5. Or tap **record**, stop, then **🎓 Make lesson with Ustad** on the preview.

Convert a demo video to H.265 on Windows:

```
ffmpeg -i ac_leak.mp4 -c:v libx265 -tag:v hvc1 -vf "scale=540:960" -an ac_leak_h265.mp4
```

## Where the Ustad code lives

| File | What it does |
|---|---|
| `ustad/GeminiClient.kt` | Prompts + AI calls (inspect, lesson from frames, checkpoint check, translate); Gemini → OpenRouter → local Llama |
| `ustad/UstadBrain.kt` | Brain: Bayesian cause ranking, next best question, learning from confirmed fixes, evidence explanation |
| `ustad/Experience.kt` | Confirmed-fix memory (export/import), machine history, job reports |
| `ustad/BrainGraph.kt` | Reasoning graph: evidence → causes → parts |
| `assets/ustad/kb_graph.html` | Draws the reasoning graph |
| `ustad/MachineRouter.kt` | Which expert handles a machine (car / split AC / other) and its 3D model |
| `ustad/Lesson.kt` | Lessons, lesson store, Skill Passport |
| `ustad/VideoFrames.kt` | Key frames from an expert video (face-blurred) |
| `ui/UstadHubScreen.kt` | USTAD hub: Inspect · Teach · Lessons · Passport (works without glasses) |
| `ui/LessonStudioScreen.kt` | Teach: build a lesson from video, expert review & publish |
| `ui/LearnScreen.kt` | Learn: guided steps, why, checkpoints, quiz, Skill Passport |
| `ui/ExpertPanel.kt` | Knowledge-base diagnosis UI (questions, prove it, repair coach) |
| `assets/ustad/car.html` | 3D exploded view of a car (16 parts) |
| `assets/ustad/kb/*.json` | Knowledge bases: car, split AC (symptoms, failures, tests, fixes, sources) |
| `assets/ustad/lessons/*.json` | Built-in lessons |
| `ustad/UstadModels.kt` | JSON → diagnosis / repair models |
| `ustad/UstadVoice.kt` | Ustad's voice: ElevenLabs (Flash v2.5 / v3 by language) or phone voice, with "finished speaking" callback |
| `ustad/UstadEar.kt` | Listening: Android speech recognition in the coaching language |
| `ustad/BatteryModel.kt` | Battery health verdict, remaining cycles, failing layer |
| `ustad/MachineEar.kt` | 🎧 Listen: recording + sound symptoms |
| `ustad/AudioCodec.kt` | ADTS/WAV encoding, loudness/pitch/spectrum (no Android deps) |
| `ustad/PlateText.kt` | Which text in a photo is a number plate |
| `ustad/Dashboard.kt` | Team dashboard data, skill badges, records export/import |
| `ustad/UstadLens.kt` | Ray-Ban Display: Ustad in the lens |
| `ui/BatteryPanel.kt`, `ui/ListenCard.kt`, `ui/ExpertCall.kt` | Battery module, Listen, Live expert |
| `assets/ustad/battery.html`, `assets/ustad/dashboard.html` | 3D battery pack, team dashboard |
| `ustad/SafetyGuard.kt` | Fixed safety rules for AI repair steps and lesson drafts |
| `ustad/VoiceCommands.kt` | What was said → command, quiz answer or question (6 languages) |
| `ui/VoiceBar.kt` | Voice loop (speak → listen → act), Talk / Hands-free / voice switch, Ask Ustad |
| `ustad/PrivacyFilter.kt` | On-device face blur before upload (ML Kit, fallback: platform detector) |
| `ui/UstadScreen.kt` | Look → Reveal → Confirm → Fix screen |
| `assets/ustad/split_ac_indoor.html` | Exploded view of a split AC indoor unit |
| `ui/CameraScreen.kt` | Hook: captured photo opens Ustad |

## Notes

- The free Gemini tier has a small daily request limit, so don't burn requests before the demo.
- Hindi and Arabic speech need those voices installed on the phone (Settings → Text-to-speech).
- The API key is compiled into the debug app. That's fine for a prototype; production would call a backend.

## Tests

```
./gradlew :app:testDebugUnitTest
```

They cover the brain (ranking, learning from confirmed fixes, explanations, reasoning graph), the battery model, number-plate detection, audio encoding and analysis, the dashboard and records sharing, safety rules, voice commands, knowledge-base data integrity (every failure maps to a part of its 3D model), machine routing (regression test for "washing m**ac**hine" being treated as an AC), lessons and the Skill Passport.
