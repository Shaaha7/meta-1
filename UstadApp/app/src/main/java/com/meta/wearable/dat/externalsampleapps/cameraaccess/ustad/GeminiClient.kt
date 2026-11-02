// GeminiClient - Calls the Gemini API with a glasses photo and the Ustad prompts
//
// Plain HttpURLConnection + org.json so the sample needs no extra dependencies. The API key and
// model come from local.properties via BuildConfig (GEMINI_API_KEY, GEMINI_MODEL). Responses are
// requested as JSON (responseMimeType) and parsed into Ustad models.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.graphics.Bitmap
import android.util.Base64
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Part IDs of the hand-built 3D/exploded models in the Ustad machine library. */
object MachineLibrary {
  const val AC_INDOOR = "split_ac_indoor"

  /** Matches assets/ustad/car.html and the "part" of each failure in kb/car.json. */
  val carParts =
      linkedMapOf(
          "c-body" to "Body shell",
          "c-hood" to "Hood (bonnet)",
          "c-bumper" to "Front bumper",
          "c-engine" to "Engine",
          "c-radiator" to "Radiator & coolant",
          "c-battery12" to "12V battery",
          "c-gearbox" to "Transmission",
          "c-suspension" to "Suspension (front strut)",
          "c-brake" to "Brakes (disc & pads)",
          "c-wheel-fl" to "Front-left wheel & tire",
          "c-wheel-fr" to "Front-right wheel & tire",
          "c-wheel-rl" to "Rear-left wheel & tire",
          "c-wheel-rr" to "Rear-right wheel & tire",
          "c-hvbattery" to "High-voltage battery (EV/hybrid)",
          "c-fuel-tank" to "Fuel tank",
          "c-exhaust" to "Exhaust",
      )

  val acParts =
      linkedMapOf(
          "p-cover" to "Front cover",
          "p-filter" to "Air filter",
          "p-coil" to "Evaporator coil",
          "p-fan" to "Blower fan",
          "p-pan" to "Drain pan",
          "p-pipe" to "Drain line / hose",
          "p-pcb" to "Control board",
          "p-louver" to "Louver",
      )
}

class GeminiException(message: String, val retryable: Boolean = false) : Exception(message)

/** Prove-it test judged from a photo (project plan, Prompt 3: Confirm). */
data class TestVerdict(val result: String, val reason: String, val nextAction: String) {
  val confirmed: Boolean
    get() = result == "CONFIRMED"
  val notConfirmed: Boolean
    get() = result == "NOT_CONFIRMED"
}

/** Result of checking a learner's checkpoint photo against what the step must show. */
data class StepCheck(val pass: Boolean, val feedback: String)

class GeminiClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val model: String = BuildConfig.GEMINI_MODEL,
    private val openRouterKey: String = BuildConfig.OPENROUTER_API_KEY,
    private val openRouterModel: String = BuildConfig.OPENROUTER_MODEL,
    private val localUrl: String = BuildConfig.LOCAL_AI_URL,
    private val localModel: String = BuildConfig.LOCAL_AI_MODEL,
) {

  companion object {
    private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash")

    /** Longest image side sent to the AI. Gemini bills/reads images in 768px tiles, so 768 = 1 tile: fastest. */
    private const val MAX_IMAGE_SIDE = 768
    private const val BACKOFF_MS = 800L
    private const val READ_TIMEOUT_MS = 30_000
  }

  val isConfigured: Boolean
    get() = apiKey.isNotBlank() || openRouterKey.isNotBlank() || localUrl.isNotBlank()

  /** True when only the laptop's local model is set up (no cloud key in local.properties). */
  private val localOnly: Boolean
    get() = apiKey.isBlank() && openRouterKey.isBlank()

  private fun partList(parts: Map<String, String>) = parts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }

  /** Seconds the last AI answer took (shown so slow answers can be diagnosed). */
  @Volatile
  var lastSeconds: Double = 0.0
    private set

  /** Models that rejected the speed setting (thinkingConfig); they are called without it. */
  private val noThinkingConfig = java.util.Collections.synchronizedSet(mutableSetOf<String>())

  /** Which provider and model produced the last answer, shown to the user for transparency. */
  @Volatile
  var lastProvider: String = ""
    private set

  suspend fun inspect(photo: Bitmap, technicianNote: String, kbSymptoms: Collection<KbSymptom> = emptyList()): Inspection {
    val visual = kbSymptoms.filter { it.visual }.distinctBy { it.id }.joinToString("\n") { "- ${it.id}: ${it.label}" }
    val prompt =
        """
        You are Ustad, a master technician with 30 years of experience, looking through a
        technician's smart glasses. Inspect the photo.

        1. Identify the machine: type, brand, model (read nameplates, labels, QR codes).
        2. List only symptoms that are actually visible (leaks, drips, stains, smoke, corrosion,
           cracks, burn marks, loose parts, error codes, indicator lights). Never invent symptoms.
        3. Reason like an expert: map the outside symptoms to the INTERNAL parts that cause them.
           Rank the 3 most likely root causes. For each give the cause chain
           (root cause -> effect -> visible symptom) and a quick SAFE test the technician can do
           in under 1 minute to confirm it, with the expected result if it is true.
        4. Flag any safety hazard (electricity, gas, refrigerant, high pressure, battery).

        If the machine is a CAR (any car, SUV, pickup, EV or hybrid), part_id MUST be one of:
        ${partList(MachineLibrary.carParts)}
        If the machine is a split air-conditioner indoor unit, part_id MUST be one of:
        ${partList(MachineLibrary.acParts)}
        For any other machine use short kebab-case part ids of your own.
        If the photo is too unclear to judge, set need_better_view true and tell the user what to do.
        For the 3D exploded view: if the machine is a car or a split air-conditioner, return
        internal_parts as [] (Ustad has its own 3D model). Otherwise list 6 to 10 main INTERNAL parts
        in internal_parts (include every part_id used in diagnoses, same ids). shape is one of
        box, cylinder, sphere, tube. size is [width,height,depth] in rough relative units 0.2-4.
        layer orders parts from outermost front (3) to innermost/back (-3). color is a realistic hex.
        If the machine is a car or a split air-conditioner, ALSO report in kb_symptoms each of
        these ids that applies to it as "present", "absent" or "unknown" based ONLY on what is
        visible in the photo (use "unknown" when you cannot see it). Read any error or warning
        code shown into error_code.
        $visual
        If nothing visible indicates a fault, set fault_found false, return an empty diagnoses
        list, and say so in voice_summary. Leave safety.hazard empty when there is no hazard.

        Technician's note (may be empty): "$technicianNote"

        Return ONLY JSON with this shape:
        {"machine":{"type":"","brand":"","model":"","confidence":0.0},
         "symptoms":[{"symptom":"","location":"","confidence":0.0}],
         "diagnoses":[{"rank":1,"part_id":"","part_name":"","confidence":0.0,
                       "cause_chain":["",""],"confirm_test":"","expected_result_if_true":""}],
         "safety":{"hazard":"","stop_and_call_pro":false},
         "fault_found":true,
         "kb_symptoms":{"water_drip":"unknown"},"error_code":"",
         "internal_parts":[{"id":"","name":"","shape":"box","size":[1,1,1],"layer":0,"color":"#8a96a3"}],
         "voice_summary":"max 25 words, spoken to the technician",
         "need_better_view":false,"user_instruction":""}
        """
            .trimIndent()
    return parseInspection(generate(prompt, listOf(photo)))
  }

  /**
   * Capture → Lesson: turns key frames of an expert doing a job into a draft lesson the expert
   * reviews before learners see it. [frames] must already be face-blurred.
   */
  suspend fun buildLesson(frames: FrameSet, expertNote: String, language: String = "English"): Lesson {
    val timeline = frames.frames.mapIndexed { i, f -> "#${i + 1} at ${f.atSec}s" }.joinToString(", ")
    val prompt =
        """
        You are Ustad, a master technician and patient teacher. The images are key frames, in
        order, from a video recorded through the smart glasses of an EXPERT doing a real job
        ($timeline; video length ${frames.durationSec}s). Turn what the expert did into a lesson
        that will guide an APPRENTICE wearing the glasses through the same job, hands-free.

        Expert's note (may be empty, trust it over your guesses): "$expertNote"

        Rules:
        - Identify the machine and the job. Steps follow what the expert actually did, in order.
          Fill obvious gaps (e.g. safety isolation) and mark them "(added by Ustad)" in why.
        - 4 to 10 steps. say: ONE action, max 15 words, spoken to the apprentice.
        - why: one sentence on why this step matters (the reasoning an expert carries in their head).
        - safety: the hazard for this step, or "" if none.
        - Mark 1 to 3 critical steps checkpoint=true with must_show: what a photo of the result must show.
        - at_sec: the frame time where the step is seen, or -1.
        - If the machine is a CAR, part_id MUST be one of:
        ${partList(MachineLibrary.carParts)}
          If it is a split air-conditioner, part_id MUST be one of:
        ${partList(MachineLibrary.acParts)}
          Otherwise use short kebab-case ids and describe 5 to 10 internal parts in internal_parts.
        - quiz: 3 multiple-choice questions that test UNDERSTANDING (why), not memory. 3 options,
          answer is the 0-based index of the correct option, explain in one sentence.
        - Write title, summary, say, why, safety, quiz in $language.
        - If the frames don't show a clear job, return an empty steps list and explain in summary.

        Return ONLY JSON:
        {"title":"","machine":"","summary":"max 30 words",
         "steps":[{"title":"","say":"","why":"","safety":"","checkpoint":false,"must_show":"","part_id":"","at_sec":-1}],
         "quiz":[{"question":"","options":["","",""],"answer":0,"explain":""}],
         "internal_parts":[{"id":"","name":"","shape":"box","size":[1,1,1],"layer":0,"color":"#8a96a3"}]}
        """
            .trimIndent()
    val sheet = VideoFrames.contactSheet(frames.frames)
    return parseLesson(generate(prompt, frames.frames.map { it.image }, singleImage = sheet))
  }

  /**
   * Confirm: the technician did the prove-it test and shows the result. The AI only judges what
   * the photo shows; when it can't tell, the answer is UNCLEAR and says what to show instead.
   */
  suspend fun verifyTest(photo: Bitmap, machine: String, cause: String, test: String, expected: String, language: String): TestVerdict {
    val prompt =
        """
        You are Ustad. The technician did a confirmation test and photographed the result.
        Machine: $machine
        Suspected cause: $cause
        Test: $test
        Expected result if the cause is real: $expected

        Decide from the photo only:
        - CONFIRMED: the photo clearly shows the expected result.
        - NOT_CONFIRMED: the photo clearly shows the expected result did NOT happen.
        - UNCLEAR: the photo doesn't show the result well enough (or the result can't be seen in a
          photo). Then next_action says exactly what to show or check again.
        reason and next_action: max 20 words each, in $language, spoken to the technician.
        Return ONLY JSON: {"result":"UNCLEAR","reason":"","next_action":""}
        """
            .trimIndent()
    val json = generate(prompt, listOf(photo))
    val result = json.optString("result").uppercase().replace(' ', '_').let { if (it in setOf("CONFIRMED", "NOT_CONFIRMED")) it else "UNCLEAR" }
    return TestVerdict(result, json.optString("reason"), json.optString("next_action"))
  }

  /** Guide: checks a learner's checkpoint photo. Strict about safety, kind in tone. */
  suspend fun checkStep(photo: Bitmap, machine: String, step: LessonStep, language: String): StepCheck {
    val prompt =
        """
        You are Ustad checking an apprentice's work through their smart glasses.
        Machine: $machine
        Step: ${step.say}
        The photo MUST show: ${step.mustShow.ifBlank { step.say }}
        Judge ONLY from the photo. If it is unclear or does not show it, pass is false.
        feedback: max 20 words in $language, spoken to the apprentice: what is right, or exactly
        what to fix or how to show it better.
        Return ONLY JSON: {"pass":false,"feedback":""}
        """
            .trimIndent()
    val json = generate(prompt, listOf(photo))
    return StepCheck(json.optBoolean("pass", false), json.optString("feedback"))
  }

  suspend fun repairSteps(inspection: Inspection, diagnosis: Diagnosis, language: String): List<RepairStep> {
    val prompt =
        """
        You are Ustad coaching a technician through a repair. Their hands are busy and eyes are on
        the job. Write the steps in $language.
        Machine: ${inspection.machine.type} ${inspection.machine.brand} ${inspection.machine.model}
        Confirmed fault: ${diagnosis.partName} — ${diagnosis.causeChain.joinToString(" -> ")}

        Rules:
        - One action per step, max 15 words, 5 to 9 steps.
        - Always start with safety isolation (power off, valves closed, PPE).
        - Mark 2 or 3 critical steps as checkpoint with what the photo must show.
        - If the repair is unsafe or beyond a field repair, make a single step with safety_stop true
          telling them to stop and call a certified professional.

        Return ONLY JSON:
        {"steps":[{"say":"","checkpoint":false,"checkpoint_must_show":"","safety_stop":false}]}
        """
            .trimIndent()
    return parseRepairSteps(generate(prompt, emptyList()))
  }

  /**
   * Ask Ustad: a spoken question from the technician, answered from [context] (the current step,
   * cause chain, safety notes and sources) in a few spoken words. Unsafe → stop and call a pro.
   */
  suspend fun answer(question: String, context: String, language: String): String {
    val prompt =
        """
        You are Ustad, a calm master technician speaking in the ear of a technician whose hands
        are busy. They just asked: "$question"

        What you know about the current job (prefer it over general knowledge):
        $context

        Rules:
        - Answer in $language, max 35 words, plain spoken words, no lists or symbols.
        - Be practical: what to do or why, like an experienced mentor.
        - If the answer is not in the context and you are not sure, say so and suggest asking
          the trainer. Never guess about electricity, high voltage, fuel, refrigerant or pressure.
        - If what they describe is unsafe, start with "Stop." and tell them to call a
          certified professional.
        Return ONLY JSON: {"answer":""}
        """
            .trimIndent()
    return generate(prompt, emptyList()).optString("answer").ifBlank { throw GeminiException("No answer", retryable = true) }
  }

  /** Translation only: the knowledge base is written in English; the LLM must not add content. */
  suspend fun translate(lines: List<String>, language: String): List<String> {
    if (lines.isEmpty() || language == "English") return lines
    val prompt =
        """
        Translate each line into $language for a technician. Translate faithfully: do not add,
        remove or change any information. Return ONLY JSON: {"lines":["", ...]} with the same count.
        ${JSONArray(lines)}
        """.trimIndent()
    val out = generate(prompt, emptyList()).optJSONArray("lines") ?: return lines
    return if (out.length() == lines.size) List(out.length()) { out.getString(it) } else lines
  }

  /**
   * Sends [prompt] with [images] to the first AI provider that answers. Gemini receives every
   * image; OpenAI-compatible providers (OpenRouter, local Llama) receive [singleImage] instead,
   * because many vision models there accept only one image per request.
   */
  private suspend fun generate(prompt: String, images: List<Bitmap>, singleImage: Bitmap? = images.singleOrNull()): JSONObject =
      withContext(Dispatchers.IO) {
        if (!isConfigured) throw GeminiException("Add GEMINI_API_KEY, OPENROUTER_API_KEY or LOCAL_AI_URL to local.properties")
        val started = System.nanoTime()
        fun done(json: JSONObject) = json.also { lastSeconds = (System.nanoTime() - started) / 1e9 }
        val jpegs = images.map { it.toBase64Jpeg() }
        val jpeg = if (singleImage != null && images.size != 1) singleImage.toBase64Jpeg() else jpegs.singleOrNull()
        var lastError: GeminiException? = null

        // 1) Gemini. Free-tier models often answer 503 (high demand) or 429 (rate limit), so try
        // each model once with a short backoff instead of waiting on one busy model.
        if (apiKey.isNotBlank()) {
          for (candidate in (listOf(model) + FALLBACK_MODELS).distinct()) {
            try {
              val answer =
                  try {
                    callGemini(candidate, geminiBody(prompt, jpegs, thinkingFor(candidate)))
                  } catch (e: GeminiException) {
                    // A model that doesn't accept the speed setting: remember, call it without.
                    if (candidate in noThinkingConfig || !e.message.orEmpty().contains("thinking", ignoreCase = true)) throw e
                    noThinkingConfig += candidate
                    callGemini(candidate, geminiBody(prompt, jpegs, null))
                  }
              lastProvider = "Gemini · $candidate"
              return@withContext done(answer)
            } catch (e: GeminiException) {
              lastError = e
              if (!e.retryable) break
              delay(BACKOFF_MS)
            }
          }
        }

        // 2) Backup: OpenRouter (OpenAI-compatible). "openrouter/free" picks a free model that
        // supports image input and JSON output.
        if (openRouterKey.isNotBlank()) {
          try {
            return@withContext done(
                callOpenAiCompatible(
                    "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", openRouterKey, openRouterModel, prompt, jpeg))
          } catch (e: GeminiException) {
            lastError = e
          }
        }

        // 3) Offline backup: a local model (e.g. Ollama gemma3 on the laptop, reached over USB with
        // `adb reverse tcp:11434 tcp:11434`). Works with no internet at all.
        if (localUrl.isNotBlank()) {
          try {
            return@withContext done(callOpenAiCompatible("Local", localUrl, "", localModel, prompt, jpeg))
          } catch (e: GeminiException) {
            lastError =
                if (localOnly)
                    GeminiException(
                        "No cloud AI key is set and the local AI at $localUrl didn't answer. Add GEMINI_API_KEY " +
                            "to local.properties, or start Ollama and run: adb reverse tcp:11434 tcp:11434")
                else e
          }
        }
        throw lastError ?: GeminiException("No AI provider answered")
      }

  /**
   * Speed: Gemini 3 models "think" before answering by default, which is what made inspection
   * slow. Ustad's prompts are structured and the knowledge-base brain does the hard reasoning, so
   * low thinking is enough. Gemini 3 takes thinkingLevel; Gemini 2.5 Flash takes thinkingBudget
   * (0 = off). Setting both is an API error, so exactly one is sent.
   */
  private fun thinkingFor(model: String): JSONObject? =
      when {
        model in noThinkingConfig -> null
        model.contains("2.5") || model.contains("2.0") -> JSONObject().put("thinkingBudget", 0)
        else -> JSONObject().put("thinkingLevel", "low")
      }

  private fun geminiBody(prompt: String, jpegs: List<String>, thinking: JSONObject? = null): JSONObject {
    val parts = JSONArray().put(JSONObject().put("text", prompt))
    jpegs.forEach { jpeg ->
      parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", jpeg)))
    }
    return JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        .put(
            "generationConfig",
            JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2).apply {
              if (thinking != null) put("thinkingConfig", thinking)
            },
        )
  }

  private fun callGemini(model: String, body: JSONObject): JSONObject {
    val text =
        post(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to apiKey),
            body,
            "Gemini",
        )
    val answer =
        JSONObject(text)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?: throw GeminiException("Empty answer from Gemini", retryable = true)
    return extractJson(answer)
  }

  private fun callOpenAiCompatible(
      provider: String,
      endpoint: String,
      key: String,
      model: String,
      prompt: String,
      jpeg: String?,
  ): JSONObject {
    val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
    if (jpeg != null) {
      content.put(
          JSONObject()
              .put("type", "image_url")
              .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpeg")),
      )
    }
    val body =
        JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.2)
    val headers = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key", "X-Title" to "Ustad")
    val text = post(endpoint, headers, body, provider)
    val json = JSONObject(text)
    val answer =
        json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw GeminiException("Empty answer from $provider")
    lastProvider = "$provider · ${json.optString("model", model)}"
    return extractJson(answer)
  }

  /** POSTs JSON and returns the response body, mapping HTTP/network failures to GeminiException. */
  private fun post(endpoint: String, headers: Map<String, String>, body: JSONObject, provider: String): String {
    val conn =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 10_000
          readTimeout = if (body.toString().length > 400_000) READ_TIMEOUT_MS * 3 else READ_TIMEOUT_MS
          doOutput = true
          setRequestProperty("Content-Type", "application/json")
          headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    return try {
      conn.outputStream.use { it.write(body.toString().toByteArray()) }
      val code = conn.responseCode
      val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) {
        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
        throw GeminiException("$provider error $code: $msg", retryable = code == 429 || code >= 500)
      }
      text
    } catch (e: java.io.IOException) {
      throw GeminiException("$provider network problem: ${e.message}", retryable = true)
    } finally {
      conn.disconnect()
    }
  }

  /** Models sometimes wrap JSON in prose or code fences; keep only the outermost object. */
  private fun extractJson(answer: String): JSONObject {
    val start = answer.indexOf('{')
    val end = answer.lastIndexOf('}')
    if (start < 0 || end <= start) throw GeminiException("AI answer was not JSON", retryable = true)
    return JSONObject(answer.substring(start, end + 1))
  }

  private fun Bitmap.toBase64Jpeg(): String {
    // Downscale to keep uploads small and fast (one 768px tile for Gemini).
    val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(width, height)
    val img = if (scale < 1f) Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true) else this
    val out = ByteArrayOutputStream()
    img.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }
}
