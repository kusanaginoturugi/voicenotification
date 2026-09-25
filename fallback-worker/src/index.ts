const RSS_URL = "https://www.nhk.or.jp/rss/news/cat0.xml";
const NEWS_PATHS = new Set(["/", "/v1/news", "/v1/news/"]);
const MAX_FEED_BYTES = 256 * 1024;
const CACHE_SECONDS = 15 * 60;
const CACHE_VERSION = "v5";
const PROMPT = `以下は日本のニュース見出しと概要の一覧です。音声で読み上げるために、全体を 3〜4 文の自然な日本語にまとめてください。固有名詞は残し、数字は算用数字で。summary は必ず「ニュースです。」で始め、箇条書き・記号・Markdown・読み指定の記法を含めないでください。

JSON オブジェクトだけを返してください。キーは summary と readings です。readings は、summary に実際に含まれる難読な固有名詞（人名・組織名・地名・作品名）だけの配列です。一般名詞・漢字熟語・肩書きは絶対に含めないでください。例えば台風、晩餐会、軍国主義、死去、審査申請、問責決議案は対象外です。各要素は surface（本文と完全一致する固有名詞だけ。肩書きは含めない）と reading（全角カタカナの読み）を持ちます。読みが不要なら空配列にしてください。指示の説明、記事一覧、下書き、思考過程は絶対に出力しないでください。`;

export default {
  async fetch(request, env, ctx): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "GET" || !NEWS_PATHS.has(url.pathname)) {
      return json({ error: "not found" }, 404);
    }
    if (!hasValidToken(request, env.DEVICE_TOKEN)) {
      return json({ error: "unauthorized" }, 401);
    }

    const count = parseCount(url.searchParams.get("count"));
    const cacheKey = new Request(`${url.origin}${url.pathname}?v=${CACHE_VERSION}&count=${count}`);
    const cached = await caches.default.match(cacheKey);
    if (cached) return cached;

    try {
      const articles = await fetchArticles(count);
      if (articles.length === 0) throw new Error("RSS に記事がありません");
      const summary = await summarize(articles, env.GEMINI_API_KEY, env.GEMINI_MODEL);
      const response = new Response(summary, {
        headers: {
          "content-type": "text/plain; charset=utf-8",
          "cache-control": `public, max-age=${CACHE_SECONDS}`,
        },
      });
      ctx.waitUntil(caches.default.put(cacheKey, response.clone()));
      console.log(JSON.stringify({ event: "summary_created", count }));
      return response;
    } catch (error) {
      console.error(JSON.stringify({ event: "summary_failed", message: errorMessage(error) }));
      return json({ error: "summary unavailable" }, 502);
    }
  },
} satisfies ExportedHandler<Env>;

function hasValidToken(request: Request, expected: string): boolean {
  const token = request.headers.get("authorization")?.match(/^Bearer (.+)$/)?.[1];
  if (!token) return false;
  const actualBytes = new TextEncoder().encode(token);
  const expectedBytes = new TextEncoder().encode(expected);
  return actualBytes.byteLength === expectedBytes.byteLength &&
    crypto.subtle.timingSafeEqual(actualBytes, expectedBytes);
}

function parseCount(value: string | null): number {
  const count = Number.parseInt(value ?? "7", 10);
  return Number.isInteger(count) && count >= 1 && count <= 10 ? count : 7;
}

async function fetchArticles(count: number): Promise<string[]> {
  const response = await fetch(RSS_URL, {
    headers: { "user-agent": "VoiceNotification fallback/1.0" },
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new Error(`RSS HTTP ${response.status}`);
  return parseRss(await readLimitedText(response, MAX_FEED_BYTES), count);
}

async function readLimitedText(response: Response, maxBytes: number): Promise<string> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error("RSS body が空です");
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel("RSS が大きすぎます");
        throw new Error("RSS が大きすぎます");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

function parseRss(xml: string, count: number): string[] {
  return [...xml.matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/gi)]
    .slice(0, count)
    .map((match) => {
      const item = match[1];
      const title = xmlValue(item, "title");
      const description = xmlValue(item, "description");
      return [title, description].filter(Boolean).join("\n");
    })
    .filter(Boolean);
}

function xmlValue(item: string, tag: string): string {
  const match = item.match(new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)<\\/${tag}>`, "i"));
  return match ? cleanText(match[1]) : "";
}

function cleanText(value: string): string {
  return value
    .replace(/^<!\[CDATA\[([\s\S]*)\]\]>$/, "$1")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/\s+/g, " ")
    .trim();
}

async function summarize(articles: string[], apiKey: string, model: string): Promise<string> {
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-goog-api-key": apiKey,
        "x-goog-api-client": "showway-voicenotification/1.0",
      },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: PROMPT }] },
        contents: [{ role: "user", parts: [{ text: articles.join("\n\n") }] }],
        generationConfig: {
          temperature: 0.3,
          maxOutputTokens: 600,
          responseMimeType: "application/json",
          thinkingConfig: { thinkingLevel: "minimal" },
        },
      }),
      signal: AbortSignal.timeout(30_000),
    },
  );
  if (!response.ok) {
    const detail = await readLimitedText(response, 8 * 1024).catch(() => "");
    throw new Error(`Gemini HTTP ${response.status}: ${cleanText(detail).slice(0, 500)}`);
  }
  const body = await response.json() as GeminiResponse;
  const text = body.candidates?.[0]?.content?.parts
    ?.filter((part) => !part.thought)
    .map((part) => part.text ?? "")
    .join("")
    .trim();
  if (!text) throw new Error("Gemini の要約が空です");
  return applyReadings(parseSummary(text));
}

function parseSummary(text: string): SummaryResult {
  let result: unknown;
  try {
    result = JSON.parse(text);
  } catch {
    throw new Error("Gemini の要約が JSON ではありません");
  }
  if (Array.isArray(result) && result.length === 1) result = result[0];
  if (!isSummaryResult(result)) throw new Error(`Gemini の要約 JSON が不正です: ${describeSummaryShape(result)}`);
  const summary = result.summary.replace(/\s+/g, " ").trim();
  if (!summary.startsWith("ニュースです。") || summary.includes("[[") || summary.length > 1_200) {
    throw new Error("Gemini の要約本文が不正です");
  }
  return { summary, readings: result.readings.filter(isReading) };
}

function isSummaryResult(value: unknown): value is { summary: string; readings: unknown[] } {
  return typeof value === "object" && value !== null &&
    typeof (value as { summary?: unknown }).summary === "string" &&
    Array.isArray((value as { readings?: unknown }).readings);
}

function describeSummaryShape(value: unknown): string {
  if (typeof value !== "object" || value === null) return `root=${typeof value}`;
  const record = value as Record<string, unknown>;
  return `keys=${Object.keys(record).join(",")};summary=${typeof record.summary};readings=${Array.isArray(record.readings) ? "array" : typeof record.readings}`;
}

function isReading(value: unknown): value is Reading {
  if (typeof value !== "object" || value === null) return false;
  const { surface, reading } = value as { surface?: unknown; reading?: unknown };
  return typeof surface === "string" && typeof reading === "string" &&
    /^[\p{Script=Han}々〆ヶ]{1,32}$/u.test(surface) &&
    /^[ァ-ヺー・]{1,64}$/.test(reading);
}

function applyReadings(result: SummaryResult): string {
  const entries = [...new Map(result.readings.map((entry) => [entry.surface, entry])).values()]
    .filter((entry) => result.summary.includes(entry.surface))
    .sort((a, b) => b.surface.length - a.surface.length);
  return entries.reduce(
    (summary, entry) => summary.split(entry.surface).join(`[[${entry.surface}|${entry.reading}]]`),
    result.summary,
  );
}

function json(body: Record<string, string>, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "unknown error";
}

interface GeminiResponse {
  candidates?: Array<{ content?: { parts?: Array<{ text?: string; thought?: boolean }> } }>;
}

interface Reading {
  surface: string;
  reading: string;
}

interface SummaryResult {
  summary: string;
  readings: Reading[];
}
