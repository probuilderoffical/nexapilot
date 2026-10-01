# NexaPilot

NexaPilot is a general-purpose AI agent platform for controlling Windows and Android devices, running tasks, coding, client hunting, content workflows, and future tool modules.

## Monorepo layout

- `web/` — dashboard/PWA
- `windows-agent/` — Windows desktop controller
- `android-agent/` — Android controller
- `shared/` — shared protocol/types
- `docs/` — architecture and setup notes

## Backend

Supabase provides authentication, database, realtime task delivery, and secure server-side functions.

## Security

- Never commit private API keys or service-role keys.
- Frontend may only use Supabase publishable keys.
- Sensitive AI/API calls should run server-side through Supabase Edge Functions.
- High-risk device actions should require explicit approval.
