import { NextResponse } from "next/server";

import {
  isValidTencentPushToken,
  waitForTencentPush,
} from "@/lib/server/tencent-push-relay";

export const dynamic = "force-dynamic";
export const maxDuration = 30;

export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { token?: unknown } | null;
  if (!isValidTencentPushToken(body?.token)) {
    return NextResponse.json({ ok: false, error: "invalid token" }, { status: 400 });
  }
  const message = await waitForTencentPush(body.token, request.signal);
  if (!message) return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
  return NextResponse.json({ ok: true, message }, { headers: { "Cache-Control": "no-store" } });
}
