# Frontend plan — Next.js App Router

## Purpose and boundaries

An accessible, understandable UI makes the five Java networking concepts visible to the grader and usable by operators. No browser code uses raw TCP, UDP, Java NIO or SQL. Use Spring Boot gateway endpoints (`contracts/HTTP_API_V1.md`). Avoid extra microservices, optimistic stock edits and complex state libraries until needed.

## Pages and navigation

| Route | Persona | Content | Key interactions |
| --- | --- | --- | --- |
| `/` | All | Status and navigation; initial scaffold landing page | Link into each workflow |
| `/products` | Shop, picker | Searchable product list, SKU, on-hand, reserved, available | Inspect product and start request |
| `/requests/new` | Shop | One SKU + positive integer quantity + requestId | Validate, submit once, show accepted/rejected |
| `/requests` | Shop, supervisor | Requests table and status | Filter by status, inspect request |
| `/warehouse` | Picker | Accepted and picked queue | Mark PICKED, then DISPATCHED |
| `/activity` | Supervisor | Stock movement history and network health | Observe dispatch trail and connection status |

## Components and data ownership

- `src/app/layout.tsx`: landmarks, navigation and global styling.
- `src/components/`: StockBadge, ProductTable, RequestForm, RequestStatus, ConnectionIndicator, EmptyState, ErrorSummary, LoadingSkeleton (add only as built).
- `src/lib/api/`: typed HTTP client, response validation and error mapping (planned).
- `src/lib/events/`: SSE subscription; **invalidate/refetch** after version events (planned).
- `src/lib/types/`: view DTO definitions mirroring `HTTP_API_V1.md`, not copies of socket domain entities.

Prefer server components for initial static shell and read-only pages if appropriate. Interactive request forms, filters, SSE status and mutations live in small `"use client"` components. Never expose DB passwords or raw TLS credentials in `NEXT_PUBLIC_*` variables.

## State model for every screen

- **Loading:** show progress/skeleton without fake stock numbers.
- **Empty:** explain why there is no data and how to act.
- **Success:** expose request ID and confirmed server status.
- **Validation failure:** visible label, associated help/error, focus first invalid field.
- **Network unavailable:** banner + retry, no fabricated success.
- **Uncertain submission:** preserve requestId and let user safely retry; never generate a new ID on retry.
- **Live feed disconnected:** 'Updates paused; refresh to see latest state', reconnect with backoff, refresh on reconnect.

## Modern web guidance applied

Guidance retrieved from Google's Modern Web Guidance CLI for `responsive-table`, `forms`, `accessibility`, `validate-input-after-interaction` and `accessible-error-announcement`:

- Use semantic tables with proper headers/captions and a keyboard-scrollable wrapper; mobile layouts must retain header/value associations.
- Forms use native `label`, `required`, correct input types, meaningful buttons and visible errors; quantity is a positive integer, not a string guessed by business logic.
- Defer validation messages until blur or submit, then announce them accessibly and focus the first invalid field.
- Always provide keyboard focus indicators, skip link, sufficient touch targets, good contrast and text status beyond color.
- SSE changes invalidate cached data; never increment inventory counters in browser memory as if authoritative.
- Test narrow screens (375 px), 200% zoom, keyboard-only flow and a network failure.

## Visual system

Professional, calm inventory dashboard: neutral background, ink text, teal accent and restrained semantic status colors. Compact desktop density, roomy touch inputs on small screens, strong hierarchy for SKU and quantities. Use Tailwind utility classes consistently; do not build a bespoke design system for a coursework MVP.

## Frontend release tests

Page opens; keyboard navigation works; product table usable on mobile; form validation visible; duplicate clicks do not generate different request IDs; REST errors display accurately; SSE disconnect warning and refetch behavior work; no stock mutation without server confirmation.
