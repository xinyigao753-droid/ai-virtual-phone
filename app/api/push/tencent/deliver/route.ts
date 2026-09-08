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
    return NextResponse.json({ ok: false, error: "invalid token" }, { status: 400 });
  }
  const message = normalizeTencentPushMessage(body?.payload);
  if (!message) {
    return NextResponse.json({ ok: false, error: "invalid payload" }, { status: 400 });
  }
  deliverTencentPush(body.token, message);
  return NextResponse.json({ ok: true });
}
