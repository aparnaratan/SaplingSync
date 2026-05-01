// Supabase Edge Function: diagnose-plant
// Calls Claude Haiku 4.5 with the uploaded photo and returns a structured diagnosis.
// Set ANTHROPIC_API_KEY in: Supabase Dashboard → Project Settings → Edge Functions → Secrets

import Anthropic from "npm:@anthropic-ai/sdk@0.65.0";
import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const SYSTEM = `You are an expert agricultural pathologist diagnosing plant diseases from photographs for smallholder farmers in Tamil Nadu, India.

Analyze the image together with the crop name and farmer's description. Return ONLY valid JSON matching the requested schema. Be conservative — when uncertain, set needs_expert: true so a human expert reviews the case.

Rules:
- If the image does not clearly show a plant or affected area, set disease: "Cannot diagnose", confidence: 0, needs_expert: true, and use retake_advice to tell the farmer exactly what photo to take.
- Treatment must be 2-4 specific actionable steps practical for a small farmer (low-cost organic options first; no rare chemicals or lab tests).
- Use simple language — assume limited literacy.
- Disease name must always be in English (common name).
- treatment, prevention, and retake_advice must be in the language the user requested.`;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  try {
    const apiKey = Deno.env.get("ANTHROPIC_API_KEY");
    if (!apiKey) throw new Error("ANTHROPIC_API_KEY not configured in Supabase secrets");

    const { photoUrl, cropName, details, lang, recordId } = await req.json();
    if (!photoUrl || !cropName) {
      return new Response(JSON.stringify({ error: "photoUrl and cropName required" }), {
        status: 400,
        headers: { ...cors, "Content-Type": "application/json" },
      });
    }

    const anthropic = new Anthropic({ apiKey });
    const langName = lang === "ta" ? "Tamil (use Tamil script)" : "English";
    const userText = `Crop: ${cropName}
Farmer description: ${details || "(none)"}
Output language for treatment/prevention/retake_advice: ${langName}

Diagnose this plant from the image.`;

    const response = await anthropic.messages.create({
      model: "claude-haiku-4-5",
      max_tokens: 1024,
      system: SYSTEM,
      messages: [{
        role: "user",
        content: [
          { type: "image", source: { type: "url", url: photoUrl } },
          { type: "text", text: userText },
        ],
      }],
      output_config: {
        format: {
          type: "json_schema",
          schema: {
            type: "object",
            properties: {
              disease: { type: "string", description: "concise disease/pest name in English, or 'Healthy', or 'Cannot diagnose'" },
              confidence: { type: "number", description: "0 to 1" },
              severity: { type: "string", enum: ["low", "medium", "high", "unknown"] },
              treatment: { type: "string", description: "2-4 actionable steps for the farmer" },
              prevention: { type: "string", description: "1-2 prevention tips for next season" },
              needs_expert: { type: "boolean", description: "true if uncertain or specialized testing needed" },
              retake_advice: { type: "string", description: "if image unclear, what photo to take instead; otherwise empty string" },
            },
            required: ["disease", "confidence", "severity", "treatment", "prevention", "needs_expert", "retake_advice"],
            additionalProperties: false,
          },
        },
      },
    });

    const textBlock = response.content.find((b) => b.type === "text");
    if (!textBlock || textBlock.type !== "text") throw new Error("Empty response from model");
    const dx = JSON.parse(textBlock.text);

    const escalate = dx.needs_expert === true || dx.confidence < 0.7;

    if (recordId) {
      const sb = createClient(
        Deno.env.get("SUPABASE_URL")!,
        Deno.env.get("SUPABASE_ANON_KEY")!,
        { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } },
      );
      const noteLines = [
        `Treatment: ${dx.treatment}`,
        `Prevention: ${dx.prevention}`,
      ];
      if (dx.retake_advice) noteLines.push(`Retake advice: ${dx.retake_advice}`);
      if (details) noteLines.push(`---\nFarmer notes: ${details}`);
      await sb.from("pest_checks").update({
        diagnosis: dx.disease,
        severity: dx.severity,
        diagnosed_by: escalate ? "ai-haiku-4.5+pending" : "ai-haiku-4.5",
        admin_notes: noteLines.join("\n"),
      }).eq("id", recordId);
    }

    return new Response(JSON.stringify({ ...dx, escalated: escalate }), {
      headers: { ...cors, "Content-Type": "application/json" },
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("diagnose-plant error:", msg);
    return new Response(JSON.stringify({ error: msg }), {
      status: 500,
      headers: { ...cors, "Content-Type": "application/json" },
    });
  }
});
