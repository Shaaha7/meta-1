# Ustad: research and implementation plan

Where Ustad is (v2.2), what is still missing, and how each gap gets closed: what to build, what to research, which data, and how we'll know it worked. Ordered by impact on the pitch and on a real product.

## Where we are (v2.2)

Built: inspect + 3D exploded view (car, split AC, battery pack, AI layout for anything else) · knowledge-base brain (Bayesian ranking, next-best question, learning from confirmed fixes, explanations, reasoning graph, **checks ordered by likelihood per minute**) · Listen · prove-it by photo · Safety Stop · battery module · Teach → Learn with the expert's own words · Skill Passport, badges, team dashboard · live expert via WhatsApp · Ray-Ban Display · 6 languages, wake word, Ask Ustad · on-device face and plate blur · optional backend · **accuracy test** · new design (tabs, hero, tiles, Ustad theme).

Quality today: 80+ unit tests on the logic; all Ustad screens type-checked against Compose. It has **not** yet run end-to-end on a phone in this version.

---

## 0. Proof first (this week): the most important item

| | |
|---|---|
| **Gap** | No measured accuracy, and no clean run on a phone yet. |
| **Build (done)** | In-app **Accuracy test**: pick photos with a known cause → top-1 / top-3 / MRR / seconds per photo → shareable report. The brain's on-phone learning is switched off during the test, so the score is fair. |
| **Do** | Collect **30 photos** of real, known faults (15 car, 15 AC; e.g. from workshops, with the confirmed fix noted). Run the test. Put top-3 accuracy on a slide. |
| **Then** | Grow to **200 photos**. Report a confusion list (which causes get mixed up) and fix the knowledge base where it's wrong. |
| **Metric** | Top-3 ≥ 85% on cars and ACs; time per photo ≤ 6 s. |

## 1. Truly hands-free on the glasses

| Step | How |
|---|---|
| Live inspection | Sample 1 frame every 2 s from the glasses stream (CameraViewModel already decodes it). Run a cheap on-phone "is something wrong here?" check, and send only interesting frames to the AI. |
| Glasses mic for Listen | Record over the Bluetooth hands-free profile (already used for voice), with a 16 kHz path for the sound model. |
| Checkpoints from the glasses | In Learn, "check my work" uses the glasses capture instead of the phone camera. |
| Wake word engine | Replace the restart-the-recognizer loop with an on-device keyword spotter (e.g. Picovoice Porcupine, or a small custom model): no beeps, low battery use. |
| **Metric** | A full job (inspect → fix → report) with zero phone touches. |

## 2. More machines

| Step | How |
|---|---|
| Knowledge-base authoring pipeline | An AI drafts failure modes, symptoms, likelihoods, tests and fixes from a service manual (GraphRAG style). An expert reviews it in the app, like lessons. Every entry keeps its source. |
| Lessons → knowledge base | An approved lesson with a confirmed cause adds or strengthens a failure mode. |
| Next modules (from the PDF) | Pumps & motors (bearing, cavitation, imbalance; pairs with Listen), solar inverters (error codes), EV parts. |
| **Metric** | Each new module ships only with its own accuracy test (≥ 30 photos). |

## 3. Real 3D models

| Step | How |
|---|---|
| Part-labelled GLB models | three.js `GLTFLoader` in the existing viewers. Part names map to knowledge-base part ids. Sources: manufacturer CAD where allowed, CC-licensed models, or commissioned low-poly models. |
| Lens-ready stills | Render the highlighted part from a fixed angle for Ray-Ban Display. |

## 4. Backend, cloud and accounts

| Step | How |
|---|---|
| Deploy (one click, done) | `render.yaml` + `backend/Dockerfile`. On Render: New → Blueprint → paste 3 secrets. For production, use a UAE-region host. |
| Shared brain | Confirmed cases sync to the backend; every phone learns from everyone (opt-in). |
| Accounts and roles | Technician / expert / supervisor. Lessons are approved centrally; the dashboard is live, not file-shared. |
| Security | Per-device tokens, rate limits (done), audit log, key rotation, a short threat model. |
| Compliance | UAE PDPL: consent screen, a data-retention policy (photos never stored server-side, cases anonymised), data processed in-region. |

## 5. Brain research

| Topic | Approach | Metric |
|---|---|---|
| Linked symptoms | Upgrade from the simple (naive) Bayes model to a small Bayesian network with noisy-OR links for symptoms that go together. | Top-3 accuracy on the benchmark |
| Honest confidence | Calibration: reliability diagram and expected calibration error (ECE). Fix the model so "70%" means right 70% of the time. | ECE < 0.08 |
| Test cost and value (started) | Probability ÷ minutes ordering is done. Next: full value-of-information with test costs and the risk of the repair. | Expected minutes to diagnosis |
| Active learning | Ask the technician to confirm the cases where the brain is least sure; those teach it most. | Accuracy gained per confirmed case |

## 6. Vision research

| Topic | Approach | Data |
|---|---|---|
| Better symptom detection | Fine-tune an open vision-language model (LoRA) on labelled fault photos: leaks, corrosion, tread wear, scorch marks. | Our benchmark photos + public defect sets |
| Where exactly | Segmentation of leaks and corrosion, to draw the spot on the photo. | Annotated subset |
| Learn from experts automatically | Key-step recognition and proficiency estimation from egocentric video, following **Meta's Ego-Exo4D** benchmarks, to split expert videos into steps and grade learners. | Ego-Exo4D, our lesson videos |

## 7. Sound research

| Topic | Approach | Data |
|---|---|---|
| Anomalous machine sound | Train an anomaly detector (normal vs. abnormal) per machine type, running on the phone. | **MIMII** (valves, pumps, fans, slide rails), the **DCASE** anomalous sound detection task |
| Fault type | A classifier on log-mel spectrograms for squeal / grind / rattle / hiss, combined with the brain's evidence. | Recordings collected with the Listen feature |
| **Metric** | AUC ≥ 0.85 for normal vs. abnormal on the held-out machines. | |

## 8. Battery research

| Topic | Approach | Data |
|---|---|---|
| Replace the calibrated rule with a trained model | State-of-health and remaining-life regression (Gaussian process, or a small LSTM on cycle features) with uncertainty. | **NASA Ames PCoE** Li-ion aging, **CALCE**, **Oxford** degradation datasets |
| Field inputs | Map what a technician can measure (capacity test, resistance, temperature, BMS app) to the model's features. | |
| **Metric** | Remaining-life error (RMSE) on held-out cells; the rule stays as a safety override. | |

## 9. Teaching research

| Topic | Approach |
|---|---|
| Adaptive tutoring | Knowledge tracing (Bayesian Knowledge Tracing) per skill: Ustad knows what each learner hasn't mastered and repeats it. |
| Retention | Spaced repetition of quiz questions days later. |
| Valid certification | Badges require passed checkpoints on real jobs, not only quizzes; validate against supervisor ratings. |
| **Trial** | 10 apprentices, 2 groups (with / without Ustad), on time-to-competence and first-time-fix rate. |

## 10. On-device AI

Run Llama on the phone (ExecuTorch) for offline inspection and Ask Ustad, keeping the cloud model for hard cases. **Metric:** latency and accuracy on the benchmark, compared with the cloud.

## 11. Language

Speech recognition for Gulf Arabic and Indian English in noisy workshops: evaluate word error rate on recorded commands, use a custom vocabulary for the commands, and add noise suppression before recognition.

## 12. AR on the real machine

Phone AR (ARCore): anchor the exploded part on the real machine. Ray-Ban Display shows the 2D still; the phone shows the full overlay.

---

## Timeline

| Phase | When | Deliverables |
|---|---|---|
| 0 | This week | Clean runs on the phone; 30-photo accuracy test and slide; deploy the backend |
| 1 | 2 weeks | Live inspection from the glasses; glasses mic for Listen; checkpoints from the glasses; keyword-spotter wake word |
| 2 | 1–2 months | Shared brain + accounts + live dashboard (UAE region, PDPL); GLB models; knowledge-base authoring pipeline; pumps & motors module |
| 3 | 3–6 months | Trained sound and battery models; calibrated Bayesian network; VLM fine-tune; knowledge tracing; apprentice trial |

## Numbers to track

| Area | KPI |
|---|---|
| Diagnosis | Top-1 / top-3 accuracy, expected calibration error, seconds per inspection |
| Repair | First-time-fix rate, minutes to diagnosis, repeat visits |
| Teaching | Time to competence, badge pass rate, retention after 2 weeks |
| Product | Jobs per technician per week, crash-free sessions (CrashLog), cost per inspection |
