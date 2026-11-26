/**
 * Network status and real-time SSE event contract definitions.
 * Conforms to docs/contracts/HTTP_API_V1.md.
 */

export type SseConnectionStatus = "CONNECTING" | "CONNECTED" | "DISCONNECTED" | "ERROR";

export type UpstreamFeedStatus =
  | "CONNECTING"
  | "CONNECTED"
  | "RECONNECTING"
  | "DISCONNECTED"
  | "STOPPED"
  | "UNKNOWN";

export interface NioFeedStatusInfo {
  enabled: boolean;
  status: UpstreamFeedStatus;
  connected: boolean;
  lastReceivedVersion: number;
  notificationCount: number;
  reconnectCount: number;
}

export interface SseStatusInfo {
  activeClients: number;
  broadcastCount: number;
  lastBroadcastVersion: number;
}

export interface TlsCommandStatusInfo {
  status: string;
  owner: string;
  serverTime?: string;
  clockSkewMs?: number;
}

export interface NetworkStatusResponse {
  centralHost: string;
  nioPort: number;
  nioFeed: NioFeedStatusInfo;
  sse: SseStatusInfo;
  tlsCommand: TlsCommandStatusInfo;
}

export interface InvalidationEvent {
  version: number;
  receivedAt: Date;
  source: "SSE_EVENT" | "RECONNECT" | "PAGE_FOCUS" | "MANUAL";
}
