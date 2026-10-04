import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

// ── Environment & Secrets (Read exclusively from Deno.env) ───────────────────
const BOT_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN") || "";
const ADMIN_TELEGRAM_ID = (Deno.env.get("ADMIN_TELEGRAM_ID") || Deno.env.get("ADMIN_CHAT_ID"))?.trim() || "";
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
    return await res.json();
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
  return await sendTelegramRequest("sendMessage", payload);
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
  return await sendTelegramRequest("editMessageText", payload);
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
  View user profile nickname, key status, and expiration.
  <i>Example:</i> <code>/user user@example.com</code>

• <code>/unused</code>
  List up to 20 unused keys with total count.

• <code>/stats</code>
  View system statistics (total, unused, active users, expired, revoked).

• <code>/help</code>
  Show this command guide.`;
}

// ── HTTP Request Handler ─────────────────────────────────────────────────────

serve(async (req: Request) => {
  // Only accept POST requests
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  // 1. Verify Telegram Webhook Secret Token header
  const secretHeader = req.headers.get("x-telegram-bot-api-secret-token");
  if (!WEBHOOK_SECRET || secretHeader !== WEBHOOK_SECRET) {
    return new Response("Unauthorized", { status: 401 });
  }

  let update: any = null;
  try {
    update = await req.json();
  } catch {
    return new Response("Invalid JSON payload", { status: 400 });
  }

  // 2. Authenticate Sender: Strictly compare sender ID with ADMIN_TELEGRAM_ID
  const senderId: number | undefined =
    update.message?.from?.id ?? update.callback_query?.from?.id;

  if (!senderId || String(senderId) !== ADMIN_TELEGRAM_ID) {
    // SILENT IGNORE: Do NOT reply, do NOT acknowledge presence to unauthorized users.
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  }

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
  });

  const chatId: number =
    update.message?.chat?.id ?? update.callback_query?.message?.chat?.id;

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
    console.error("Unhandled error processing update:", err?.message || err);
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
  const command = parts[0]?.toLowerCase() || "";

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

      const email = parts[1].trim().toLowerCase();
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

      const email = parts[1].trim().toLowerCase();
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

      const email = parts[1].trim().toLowerCase();
      if (!EMAIL_REGEX.test(email)) {
        await sendMessage(chatId, `❌ Galat email format: <code>${escapeHtml(email)}</code>`);
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
        await sendMessage(chatId, `❌ ${escapeHtml(info?.message || "User nahi mila")}`);
        return;
      }

      const statusUpper = String(info.status || "NONE").toUpperCase();
      const expiryLabel = info.expires_at
        ? new Date(info.expires_at).toUTCString()
        : (info.status.includes("permanent") ? "Permanent (Never)" : "N/A");
      const redeemedLabel = info.redeemed_at
        ? new Date(info.redeemed_at).toUTCString()
        : "N/A";
      const keyMasked = info.key_last4 ? `<code>...${info.key_last4}</code>` : "None";

      const userText =
        `<b>👤 User Status</b>\n` +
        `• <b>Email:</b> <code>${escapeHtml(info.email)}</code>\n` +
        `• <b>Nickname:</b> ${escapeHtml(info.nickname)}\n` +
        `• <b>Key Status:</b> <b>${escapeHtml(statusUpper)}</b>\n` +
        `• <b>Active Key:</b> ${keyMasked}\n` +
        `• <b>Expires:</b> <code>${escapeHtml(expiryLabel)}</code>\n` +
        `• <b>Redeemed At:</b> <code>${escapeHtml(redeemedLabel)}</code>`;

      await sendMessage(chatId, userText);
      break;
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
