import { NextResponse } from "next/server";

import {
  deliverTencentPush,
  isValidTencentPushToken,
  normalizeTencentPushMessage,
} from "@/lib/server/tencent-push-relay";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const contentLength = Number(request.headers.get("content-length")) || 0;
  if (contentLength > 16_384) {
    return NextResponse.json({ ok: false, error: "payload too large" }, { status: 413 });
  }
  const body = await request.json().catch(() => null) as { token?: unknown; payload?: unknown } | null;
  if (!isValidTencentPushToken(body?.token)) {
    console.warn("[push-relay] deliver rejected: invalid token");
    return NextResponse.json({ ok: false, error: "invalid token" }, { status: 400 });
  }
  const message = normalizeTencentPushMessage(body?.payload);
  if (!message) {
    console.warn("[push-relay] deliver rejected: invalid payload", { tokenSuffix: body.token.slice(-8) });
    return NextResponse.json({ ok: false, error: "invalid payload" }, { status: 400 });
  }
  deliverTencentPush(body.token, message);
  console.info("[push-relay] queued", {
    tokenSuffix: body.token.slice(-8),
    type: message.type || "message",
    bodyLength: message.body.length,
  });
  return NextResponse.json({ ok: true });
}
    
