import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

// ── Environment & Secrets (Read exclusively from Deno.env) ───────────────────
const BOT_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN") || "";
const ADMIN_TELEGRAM_ID = (Deno.env.get("ADMIN_TELEGRAM_ID") || Deno.env.get("ADMIN_CHAT_ID"))?.trim().replace(/^["']|["']$/g, "") || "";
const WEBHOOK_SECRET = Deno.env.get("TELEGRAM_WEBHOOK_SECRET") || "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";

const TELEGRAM_API = `https://api.telegram.org/bot${BOT_TOKEN}`;

// Key format regex: NTFY-XXXX-XXXX-XXXX with unambiguous characters
const KEY_REGEX = /^NTFY-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}$/i;
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// ── Helper Functions ─────────────────────────────────────────────────────────

function escapeHtml(str: string): string {
  if (!str) return "";
  return String(str)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

async function sendTelegramRequest(method: string, payload: Record<string, unknown>): Promise<any> {
  if (!BOT_TOKEN) {
    console.error("TELEGRAM_BOT_TOKEN is not configured");
    return null;
  }
  try {
    const res = await fetch(`${TELEGRAM_API}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const json = await res.json();
    if (!res.ok || !json?.ok) {
      console.error(`Telegram API error on ${method}: HTTP ${res.status}`, JSON.stringify(json));
    }
    return json;
  } catch (err) {
    console.error(`Telegram API error on ${method}:`, err);
    return null;
  }
}

async function sendMessage(chatId: number | string, text: string, replyMarkup?: unknown): Promise<any> {
  const payload: Record<string, unknown> = {
    chat_id: chatId,
    text: text,
    parse_mode: "HTML",
  };
  if (replyMarkup) {
    payload.reply_markup = replyMarkup;
  }
  const result = await sendTelegramRequest("sendMessage", payload);
  if (!result || !result.ok) {
    console.warn(`sendMessage with HTML failed (${result?.description}). Retrying without parse_mode...`);
    const plainPayload: Record<string, unknown> = {
      chat_id: chatId,
      text: text.replace(/<[^>]+>/g, ""),
    };
    if (replyMarkup) {
      plainPayload.reply_markup = replyMarkup;
    }
    return await sendTelegramRequest("sendMessage", plainPayload);
  }
  return result;
}

async function editMessageText(
  chatId: number | string,
  messageId: number,
  text: string,
  replyMarkup?: unknown
): Promise<any> {
  const payload: Record<string, unknown> = {
    chat_id: chatId,
    message_id: messageId,
    text: text,
    parse_mode: "HTML",
  };
  if (replyMarkup !== undefined) {
    payload.reply_markup = replyMarkup;
  }
  const result = await sendTelegramRequest("editMessageText", payload);
  if (!result || !result.ok) {
    console.warn(`editMessageText with HTML failed (${result?.description}). Retrying without parse_mode...`);
    const plainPayload: Record<string, unknown> = {
      chat_id: chatId,
      message_id: messageId,
      text: text.replace(/<[^>]+>/g, ""),
    };
    if (replyMarkup !== undefined) {
      plainPayload.reply_markup = replyMarkup;
    }
    return await sendTelegramRequest("editMessageText", plainPayload);
  }
  return result;
}

async function answerCallbackQuery(callbackQueryId: string, text?: string): Promise<any> {
  const payload: Record<string, unknown> = { callback_query_id: callbackQueryId };
  if (text) {
    payload.text = text;
  }
  return await sendTelegramRequest("answerCallbackQuery", payload);
}

async function sendTyping(chatId: number | string): Promise<void> {
  await sendTelegramRequest("sendChatAction", { chat_id: chatId, action: "typing" });
}

async function logAdminAction(
  supabase: any,
  action: string,
  args: Record<string, unknown>,
  telegramUserId: number
): Promise<void> {
  try {
    await supabase.from("admin_log").insert({
      action: action,
      args: args,
      telegram_user_id: telegramUserId,
    });
  } catch (err) {
    console.error("Failed to write to admin_log:", err);
  }
}

function getHelpText(): string {
  return `<b>🔑 NotiFy Admin Bot</b>

<b>Available Commands:</b>
• <code>/genkey &lt;count&gt; &lt;duration&gt; [note]</code>
  Generate access keys (max 20).
  <i>Duration:</i> <code>perm</code> | <code>1d</code> | <code>2d</code> | <code>7d</code> | <code>&lt;N&gt;d</code>
  <i>Example:</i> <code>/genkey 5 7d friends batch</code>

• <code>/revoke &lt;KEY&gt;</code>
  Revoke an access key (requires interactive confirmation).
  <i>Example:</i> <code>/revoke NTFY-ABCD-EFGH-JKLM</code>

• <code>/revokeuser &lt;email&gt;</code>
  Revoke all active keys for a user (requires interactive confirmation).
  <i>Example:</i> <code>/revokeuser user@example.com</code>

• <code>/extend &lt;email&gt; &lt;days&gt;</code>
  Extend user's active key by N days.
  <i>Example:</i> <code>/extend user@example.com 30</code>

• <code>/user &lt;email&gt;</code>
  View full user profile, key status, app usage time, last seen, and days inactive.
  <i>Example:</i> <code>/user user@example.com</code>

• <code>/users [limit]</code>
  List recent users with their active keys and last seen activity.
  <i>Example:</i> <code>/users 10</code>

• <code>/unused</code>
  List up to 20 unused keys with total count.

• <code>/stats</code>
  View system statistics (DAU today, total users, playlists, keys).

• <code>/help</code>
  Show this command guide.`;
}

// ── HTTP Request Handler ─────────────────────────────────────────────────────

Deno.serve(async (req: Request) => {
  // Only accept POST requests
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  // 1. Verify Telegram Webhook Secret Token header (only if WEBHOOK_SECRET is configured)
  if (WEBHOOK_SECRET) {
    const secretHeader = req.headers.get("x-telegram-bot-api-secret-token");
    if (secretHeader !== WEBHOOK_SECRET) {
      console.warn("Unauthorized webhook call: invalid x-telegram-bot-api-secret-token header");
      return new Response("Unauthorized", { status: 401 });
    }
  }

  let update: any = null;
  try {
    update = await req.json();
  } catch {
    return new Response("Invalid JSON payload", { status: 400 });
  }

  const senderId: number | undefined =
    update.message?.from?.id ?? update.callback_query?.from?.id;

  const chatId: number =
    update.message?.chat?.id ?? update.callback_query?.message?.chat?.id;

  // 2. Authenticate Sender: Compare sender ID with configured admin ID
  if (!ADMIN_TELEGRAM_ID) {
    console.error("ADMIN_TELEGRAM_ID or ADMIN_CHAT_ID is not configured in Supabase secrets!");
    if (chatId) {
      await sendMessage(
        chatId,
        `⚠️ <b>Setup Incomplete:</b> Admin ID is not configured in Supabase secrets.\nYour Telegram Numeric ID is: <code>${senderId}</code>\n\nPlease set <code>ADMIN_TELEGRAM_ID="${senderId}"</code> in Supabase secrets.`
      );
    }
    return new Response(JSON.stringify({ ok: true, setup_needed: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  }

  if (!senderId || String(senderId) !== ADMIN_TELEGRAM_ID) {
    console.log(`Unauthorized sender: senderId=${senderId}, expected adminId=${ADMIN_TELEGRAM_ID}`);
    if (chatId) {
      await sendMessage(
        chatId,
        `⛔ <b>Access Denied:</b> This bot is configured for a specific admin.\n\nYour Telegram User ID: <code>${senderId}</code>\n\nIf you are the owner, set this ID in Supabase secrets:\n<code>ADMIN_TELEGRAM_ID="${senderId}"</code>`
      );
    }
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  }

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
  });

  try {
    // 3. Deduplication Check: Insert update_id into processed_updates
    const updateId = update.update_id;
    if (typeof updateId === "number") {
      const { error: dedupeErr } = await supabase
        .from("processed_updates")
        .insert({ update_id: updateId });

      if (dedupeErr) {
        // Unique constraint violation (23505) = duplicate update, return 200 immediately
        if (dedupeErr.code === "23505") {
          console.log(`Update ${updateId} already processed. Skipping duplicate execution.`);
          return new Response(JSON.stringify({ ok: true, deduplicated: true }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          });
        }
      }
    }

    // 4. Handle Callback Queries (Interactive confirmation buttons)
    if (update.callback_query) {
      await handleCallbackQuery(supabase, update.callback_query, senderId, chatId);
      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    // 5. Handle Text Messages & Commands
    if (update.message?.text) {
      await sendTyping(chatId);
      await handleMessage(supabase, update.message.text.trim(), senderId, chatId);
    }
  } catch (err: any) {
    console.error("Unhandled error processing update:", err?.message || err, err?.stack);
    if (chatId) {
      try {
        await sendMessage(
          chatId,
          "⚠️ <i>Error aaya: Request process karte waqt issue hua. Supabase logs check karein.</i>"
        );
      } catch {
        // Ignore failure in error reporting
      }
    }
  }

  // Always return 200 OK so Telegram does NOT retry and loop
  return new Response(JSON.stringify({ ok: true }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
});

// ── Command Processor ────────────────────────────────────────────────────────

async function handleMessage(
  supabase: any,
  rawText: string,
  adminId: number,
  chatId: number
): Promise<void> {
  const parts = rawText.split(/\s+/);
  const command = (parts[0]?.toLowerCase() || "").split("@")[0];
  console.log(`Processing command: "${command}" from admin: ${adminId}`);

  switch (command) {
    case "/start":
    case "/help": {
      await sendMessage(chatId, getHelpText());
      break;
    }

    case "/genkey": {
      // Usage: /genkey <count> <duration> [note]
      if (parts.length < 3) {
        await sendMessage(
          chatId,
          "❌ <b>Format:</b> <code>/genkey &lt;count&gt; &lt;duration&gt; [note]</code>\n" +
            "<i>Duration:</i> <code>perm</code> | <code>1d</code> | <code>2d</code> | <code>7d</code> | <code>&lt;N&gt;d</code>\n" +
            "<i>Example:</i> <code>/genkey 5 7d friends batch</code>"
        );
        return;
      }

      // Validate count: integer between 1 and 20 (strictly capped)
      const count = parseInt(parts[1], 10);
      if (isNaN(count) || count < 1 || count > 20) {
        await sendMessage(chatId, "❌ Count 1 se 20 ke beech hona chahiye (max cap: 20).");
        return;
      }

      // Validate duration
      const durStr = parts[2].toLowerCase();
      let durationDays: number | null = null;
      if (durStr === "perm" || durStr === "permanent") {
        durationDays = null;
      } else {
        const match = durStr.match(/^(\d+)d$/);
        if (!match) {
          await sendMessage(
            chatId,
            "❌ Galat duration format. Allowed: <code>perm</code>, <code>1d</code>, <code>2d</code>, <code>7d</code>, ya <code>&lt;N&gt;d</code> (e.g. <code>30d</code>)."
          );
          return;
        }
        const parsedDays = parseInt(match[1], 10);
        if (parsedDays < 1 || parsedDays > 3650) {
          await sendMessage(chatId, "❌ Days 1 se 3650 ke beech hone chahiye.");
          return;
        }
        durationDays = parsedDays;
      }

      // Optional note (truncate to 100 chars)
      const note = parts.slice(3).join(" ").trim() || null;
      const cleanNote = note ? note.substring(0, 100) : null;

      // Execute RPC: admin_generate_keys
      const { data: keys, error } = await supabase.rpc("admin_generate_keys", {
        n: count,
        duration_days: durationDays,
        note: cleanNote,
      });

      if (error || !keys) {
        console.error("admin_generate_keys RPC error:", error);
        await sendMessage(chatId, `❌ Key generation fail ho gaya: ${escapeHtml(error?.message || "Unknown error")}`);
        return;
      }

      // Format response: each key in <code> for one-tap copy
      const durationLabel = durationDays === null ? "Permanent" : `${durationDays} day(s)`;
      const noteLabel = cleanNote ? `\n<b>Note:</b> ${escapeHtml(cleanNote)}` : "";
      const keysFormatted = keys.map((k: any) => `<code>${escapeHtml(k.code)}</code>`).join("\n");

      const responseText = `<b>✅ Generated ${keys.length} Key(s)</b>\n` +
        `<b>Duration:</b> ${durationLabel}${noteLabel}\n\n` +
        keysFormatted;

      await sendMessage(chatId, responseText);

      // Audit log (never store full keys)
      await logAdminAction(
        supabase,
        "generate_keys",
        {
          count: count,
          duration_days: durationDays,
          note: cleanNote ? cleanNote.substring(0, 30) : null,
          keys_created: keys.length,
        },
        adminId
      );
      break;
    }

    case "/revoke": {
      // Usage: /revoke <KEY>
      if (parts.length < 2) {
        await sendMessage(chatId, "❌ <b>Format:</b> <code>/revoke NTFY-XXXX-XXXX-XXXX</code>");
        return;
      }

      const inputKey = parts[1].trim().toUpperCase();
      if (!KEY_REGEX.test(inputKey)) {
        await sendMessage(
          chatId,
          "❌ Galat key format. Expected format: <code>NTFY-XXXX-XXXX-XXXX</code>"
        );
        return;
      }

      // Store pending confirmation with 60-second validity
      const { data: conf, error: confErr } = await supabase
        .from("pending_confirmations")
        .insert({
          action: "revoke_key",
          target: inputKey,
          telegram_user_id: adminId,
        })
        .select("id")
        .single();

      if (confErr || !conf) {
        console.error("Failed to create confirmation:", confErr);
        await sendMessage(chatId, "❌ Confirmation request initiate nahi ho saka.");
        return;
      }

      const keyMasked = `...${inputKey.slice(-4)}`;
      const confirmText =
        `⚠️ <b>Confirm Key Revocation</b>\n\n` +
        `Kya aap sure hain ki key <code>${keyMasked}</code> ko revoke karna hai?\n\n` +
        `<i>⏳ Ye button 60 seconds me expire ho jayega.</i>`;

      const keyboard = {
        inline_keyboard: [
          [
            { text: "✅ Haan, revoke karo", callback_data: `cfm:${conf.id}` },
            { text: "❌ Cancel", callback_data: `cnl:${conf.id}` },
          ],
        ],
      };

      await sendMessage(chatId, confirmText, keyboard);
      break;
    }

    case "/revokeuser": {
      // Usage: /revokeuser <email>
      if (parts.length < 2) {
        await sendMessage(chatId, "❌ <b>Format:</b> <code>/revokeuser email@example.com</code>");
        return;
      }

      const email = parts[1].trim().replace(/^<|>$/g, "").toLowerCase();
      if (!EMAIL_REGEX.test(email) || email.length > 100) {
        await sendMessage(chatId, `❌ Galat email address format: <code>${escapeHtml(email)}</code>`);
        return;
      }

      // Store pending confirmation with 60-second validity
      const { data: conf, error: confErr } = await supabase
        .from("pending_confirmations")
        .insert({
          action: "revoke_user",
          target: email,
          telegram_user_id: adminId,
        })
        .select("id")
        .single();

      if (confErr || !conf) {
        console.error("Failed to create confirmation:", confErr);
        await sendMessage(chatId, "❌ Confirmation request initiate nahi ho saka.");
        return;
      }

      const confirmText =
        `⚠️ <b>Confirm User Revocation</b>\n\n` +
        `Kya aap sure hain ki <code>${escapeHtml(email)}</code> ke saare active keys revoke karne hain?\n\n` +
        `<i>⏳ Ye button 60 seconds me expire ho jayega.</i>`;

      const keyboard = {
        inline_keyboard: [
          [
            { text: "✅ Haan, revoke karo", callback_data: `cfm:${conf.id}` },
            { text: "❌ Cancel", callback_data: `cnl:${conf.id}` },
          ],
        ],
      };

      await sendMessage(chatId, confirmText, keyboard);
      break;
    }

    case "/extend": {
      // Usage: /extend <email> <days>
      if (parts.length < 3) {
        await sendMessage(chatId, "❌ <b>Format:</b> <code>/extend email@example.com &lt;days&gt;</code>");
        return;
      }

      const email = parts[1].trim().replace(/^<|>$/g, "").toLowerCase();
      if (!EMAIL_REGEX.test(email)) {
        await sendMessage(chatId, `❌ Galat email format: <code>${escapeHtml(email)}</code>`);
        return;
      }

      const days = parseInt(parts[2], 10);
      if (isNaN(days) || days <= 0 || days > 365) {
        await sendMessage(chatId, "❌ Extra days 1 se 365 ke beech hone chahiye.");
        return;
      }

      const { data: result, error } = await supabase.rpc("admin_extend_user", {
        p_email: email,
        extra_days: days,
      });

      if (error) {
        console.error("admin_extend_user error:", error);
        await sendMessage(chatId, `❌ Extension fail ho gaya: ${escapeHtml(error.message)}`);
        return;
      }

      if (!result?.success) {
        await sendMessage(chatId, `❌ ${escapeHtml(result?.message || "User update fail ho gaya")}`);
        return;
      }

      const newExpiry = result.new_expires_at
        ? new Date(result.new_expires_at).toUTCString()
        : "Permanent";

      const resText =
        `<b>✅ User License Extended</b>\n` +
        `• <b>Email:</b> <code>${escapeHtml(email)}</code>\n` +
        `• <b>Extra Days:</b> +${days}\n` +
        `• <b>New Expiry:</b> <code>${escapeHtml(newExpiry)}</code>`;

      await sendMessage(chatId, resText);

      await logAdminAction(
        supabase,
        "extend_user",
        { email: email, extra_days: days },
        adminId
      );
      break;
    }

    case "/user": {
      // Usage: /user <email>
      if (parts.length < 2) {
        await sendMessage(chatId, "❌ <b>Format:</b> <code>/user email@example.com</code>");
        return;
      }

      const email = parts[1].trim().replace(/^<|>$/g, "").toLowerCase();
      if (!EMAIL_REGEX.test(email)) {
        await sendMessage(chatId, `❌ Invalid email format: <code>${escapeHtml(email)}</code>`);
        return;
      }

      const { data: info, error } = await supabase.rpc("admin_get_user_info", {
        p_email: email,
      });

      if (error) {
        console.error("admin_get_user_info error:", error);
        await sendMessage(chatId, `❌ Lookup error: ${escapeHtml(error.message)}`);
        return;
      }

      if (!info || !info.found) {
        await sendMessage(chatId, `❌ ${escapeHtml(info?.message || "User not found")}`);
        return;
      }

      const statusUpper = String(info.status || "NONE").toUpperCase();
      const isPermanent = String(info.status || "").toLowerCase().includes("permanent");
      const expiryLabel = info.expires_at
        ? new Date(info.expires_at).toUTCString()
        : (isPermanent ? "Permanent (Never)" : "N/A");
      const redeemedLabel = info.redeemed_at
        ? new Date(info.redeemed_at).toUTCString()
        : "N/A";
      const keyMasked = info.key_last4 ? `<code>...${info.key_last4}</code>` : "None";

      const lastSeenLabel = info.last_seen_at
        ? new Date(info.last_seen_at).toUTCString()
        : "Never (No activity logged)";
      const inactiveLabel = info.days_inactive !== null && info.days_inactive !== undefined
        ? (info.days_inactive === 0 ? "Active today (0 days)" : `${info.days_inactive} days ago`)
        : "N/A";

      const userText =
        `<b>👤 User Status & Telemetry</b>\n\n` +
        `• <b>Email:</b> <code>${escapeHtml(info.email)}</code>\n` +
        `• <b>Nickname:</b> ${escapeHtml(info.nickname || "Not set")}\n` +
        `• <b>Key Status:</b> <b>${escapeHtml(statusUpper)}</b>\n` +
        `• <b>Active Key:</b> ${keyMasked}\n` +
        `• <b>Expires:</b> <code>${escapeHtml(expiryLabel)}</code>\n` +
        `• <b>Redeemed At:</b> <code>${escapeHtml(redeemedLabel)}</code>\n\n` +
        `<b>📱 Activity & Usage:</b>\n` +
        `• <b>Last Seen:</b> <code>${escapeHtml(lastSeenLabel)}</code>\n` +
        `• <b>Inactivity:</b> <b>${escapeHtml(inactiveLabel)}</b>\n` +
        `• <b>Total Active Time:</b> <code>${info.total_active_hours ?? 0} hrs</code>\n` +
        `• <b>Total Music Play:</b> <code>${info.total_play_hours ?? 0} hrs</code>\n` +
        `• <b>Daily Average:</b> <code>${info.avg_daily_active_mins ?? 0} mins/day</code>\n` +
        `• <b>Today Active:</b> <code>${info.today_active_mins ?? 0} mins</code>\n` +
        `• <b>Cloud Playlists:</b> <code>${info.cloud_playlists ?? 0}</code>\n` +
        `• <b>App Version:</b> <code>${escapeHtml(info.app_version ?? "Unknown")}</code>`;

      await sendMessage(chatId, userText);
      break;
    }

    case "/users": {
      const limit = parseInt(parts[1], 10) || 10;
      const { data: userList, error } = await supabase.rpc("admin_list_users", {
        p_limit: Math.min(Math.max(limit, 1), 25),
      });

      if (error) {
        console.error("admin_list_users error:", error);
        await sendMessage(chatId, `❌ Error fetching users: ${escapeHtml(error.message)}`);
        return;
      }

      if (!userList || userList.length === 0) {
        await sendMessage(chatId, "ℹ️ No users found.");
        return;
      }

      const rows = userList.map((u: any, idx: number) => {
        const inact = u.days_inactive !== null && u.days_inactive !== undefined
          ? (u.days_inactive === 0 ? "Today" : `${u.days_inactive}d ago`)
          : "Never";
        return `${idx + 1}. <code>${escapeHtml(u.email)}</code> (${escapeHtml(u.nickname || "No nick")})\n` +
               `   └ Key: <b>${escapeHtml(u.key_status || "NONE")}</b> | Active: <code>${u.total_active_hours ?? 0}h</code> | Last: <code>${inact}</code>`;
      });

      const responseText =
        `<b>👥 Recent Users (${userList.length}):</b>\n\n` + rows.join("\n\n");

      await sendMessage(chatId, responseText);
    }

    case "/unused": {
      // Fetch up to 20 unused keys and exact total count
      const { data: keys, count, error } = await supabase
        .from("license_keys")
        .select("code, duration_days, note, created_at", { count: "exact" })
        .is("redeemed_by", null)
        .eq("revoked", false)
        .order("created_at", { ascending: false })
        .limit(20);

      if (error) {
        console.error("Error fetching unused keys:", error);
        await sendMessage(chatId, `❌ Unused keys fetch fail ho gaya: ${escapeHtml(error.message)}`);
        return;
      }

      const totalCount = count ?? (keys?.length || 0);
      if (!keys || keys.length === 0) {
        await sendMessage(chatId, "ℹ️ Abhi koi unused key available nahi hai.");
        return;
      }

      const lines = keys.map((k: any) => {
        const dur = k.duration_days === null ? "perm" : `${k.duration_days}d`;
        const noteStr = k.note ? ` - <i>${escapeHtml(k.note)}</i>` : "";
        return `• <code>${escapeHtml(k.code)}</code> (${dur})${noteStr}`;
      });

      const responseText =
        `<b>🔑 Unused Keys (${totalCount} total, showing latest ${keys.length}):</b>\n\n` +
        lines.join("\n");

      await sendMessage(chatId, responseText);
      break;
    }

    case "/stats": {
      const { data: stats, error } = await supabase.rpc("admin_get_stats");

      if (error || !stats) {
        console.error("admin_get_stats error:", error);
        await sendMessage(chatId, `❌ Stats fetch fail ho gaya: ${escapeHtml(error?.message || "Unknown")}`);
        return;
      }

      const statsText =
        `<b>📊 NotiFy System Stats</b>\n\n` +
        `• <b>Active Users Today (DAU):</b> <b>${stats.dau_today ?? 0}</b>\n` +
        `• <b>Total Registered Users:</b> ${stats.total_registered_users ?? 0}\n` +
        `• <b>Cloud Playlists Stored:</b> ${stats.total_playlists ?? 0}\n` +
        `• <b>Total Keys:</b> ${stats.total_keys}\n` +
        `• <b>Unused Keys:</b> ${stats.unused_keys}\n` +
        `• <b>Active Keys:</b> ${stats.active_keys}\n` +
        `• <b>Active Users:</b> ${stats.active_users}\n` +
        `• <b>Expired Keys:</b> ${stats.expired_keys}\n` +
        `• <b>Revoked Keys:</b> ${stats.revoked_keys}`;

      await sendMessage(chatId, statsText);
      break;
    }

    default: {
      await sendMessage(
        chatId,
        `❓ Unknown command: <code>${escapeHtml(command)}</code>\n\n` + getHelpText()
      );
      break;
    }
  }
}

// ── Interactive Callback Query Processor ──────────────────────────────────────

async function handleCallbackQuery(
  supabase: any,
  query: any,
  adminId: number,
  chatId: number
): Promise<void> {
  const queryId = query.id;
  const messageId = query.message?.message_id;
  const dataStr: string = query.data || "";

  // Always answer query quickly to clear the Telegram loading spinner
  await answerCallbackQuery(queryId);

  const [prefix, confId] = dataStr.split(":");

  // Handle Cancel
  if (prefix === "cnl") {
    if (messageId) {
      await editMessageText(chatId, messageId, "❌ Action cancelled by admin.", null);
    }
    return;
  }

  // Handle Confirm
  if (prefix === "cfm" && confId) {
    // Atomic check-and-update enforcing:
    // 1. Single execution (idempotency against double-clicks)
    // 2. 60-second expiration window
    const cutoffIso = new Date(Date.now() - 60000).toISOString();

    const { data: updatedRows, error: updateErr } = await supabase
      .from("pending_confirmations")
      .update({ executed: true })
      .eq("id", confId)
      .eq("executed", false)
      .gt("created_at", cutoffIso)
      .select("action, target");

    if (updateErr) {
      console.error("Error executing pending confirmation:", updateErr);
      if (messageId) {
        await editMessageText(chatId, messageId, "❌ Confirmation process karte waqt error aaya.", null);
      }
      return;
    }

    if (!updatedRows || updatedRows.length === 0) {
      // Row either already executed or older than 60 seconds
      if (messageId) {
        await editMessageText(
          chatId,
          messageId,
          "❌ Ye confirmation expire ho chuki hai (60s limit) ya pehle hi execute ho chuki hai.",
          null
        );
      }
      return;
    }

    const conf = updatedRows[0];

    if (conf.action === "revoke_key") {
      const { data: result, error: rpcErr } = await supabase.rpc("admin_revoke_key", {
        p_code: conf.target,
      });

      const maskedKey = `...${conf.target.slice(-4)}`;
      if (rpcErr || !result?.success) {
        const errMsg = rpcErr?.message || result?.message || "Revoke failed";
        if (messageId) {
          await editMessageText(
            chatId,
            messageId,
            `❌ Key revocation fail: ${escapeHtml(errMsg)}`,
            null
          );
        }
        return;
      }

      if (messageId) {
        await editMessageText(
          chatId,
          messageId,
          `✅ <b>Key Revoked:</b> <code>${maskedKey}</code>\n${escapeHtml(result.message)}`,
          null
        );
      }

      await logAdminAction(
        supabase,
        "revoke_key",
        { key_last4: conf.target.slice(-4) },
        adminId
      );
    } else if (conf.action === "revoke_user") {
      const { data: result, error: rpcErr } = await supabase.rpc("admin_revoke_user", {
        p_email: conf.target,
      });

      if (rpcErr || !result?.success) {
        const errMsg = rpcErr?.message || result?.message || "User revoke failed";
        if (messageId) {
          await editMessageText(
            chatId,
            messageId,
            `❌ User revocation fail: ${escapeHtml(errMsg)}`,
            null
          );
        }
        return;
      }

      const revokedMsg =
        `✅ <b>User Revoked:</b> <code>${escapeHtml(conf.target)}</code>\n` +
        `• Keys revoked: ${result.keys_revoked || 0}`;

      if (messageId) {
        await editMessageText(chatId, messageId, revokedMsg, null);
      }

      await logAdminAction(
        supabase,
        "revoke_user",
        { email: conf.target, keys_revoked: result.keys_revoked },
        adminId
      );
    }
  }
}
