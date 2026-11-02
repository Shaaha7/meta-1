# Ustad: see inside any machine, learn by doing

Ustad is an AI master technician on Meta Ray-Ban / Oakley Meta glasses, built for Wearables Studio UAE.

- **Inspect:** look at a car (or any machine). Ustad finds the defect, explains the cause chain and shows the hidden failing part in a 3D **exploded view**.
- **Teach:** an expert wears the glasses and does the job. Ustad turns the recording into a lesson (steps, *why* each step matters, safety, photo checkpoints, quiz). The expert approves it.
- **Learn:** a learner wears the glasses and Ustad guides them step by step in English, Hindi or Arabic. It checks their work at checkpoints and records the result in a **Skill Passport**.

Trust by design: faces are blurred on the phone before anything is sent. For cars and ACs, causes are ranked by Ustad's own knowledge-base model (every answer cites a source), not guessed by the AI. Lessons are published only after an expert approves them. An offline mode runs Meta's Llama on a laptop.

| Folder | What |
|---|---|
| [`UstadApp/`](UstadApp/README.md) | Android app (Meta Wearables Device Access Toolkit). Works with **no glasses** via the in-app USTAD hub or the Mock Device Kit |
| [`backend/`](backend/README.md) | Tiny Node.js server that keeps the AI and voice API keys off the phones |
| `ustad-sim/` | Browser simulation of the glasses experience |
| `Ustad_Project_Overview.pdf` | Project overview |
