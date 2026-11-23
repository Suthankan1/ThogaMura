"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent, type RefObject } from "react";
import { ConfirmAction } from "@/components/ConfirmAction";
import { Availability, Field, field, Freshness, primaryAction, secondaryAction, textAction, Warning } from "@/components/Workspace";
import { exactProduct, workspacePage, type Read } from "@/lib/api/workspace";
import { isSku, parseQuantity } from "@/lib/reservationAttempt";
import { formatUnits } from "@/lib/formatters";
import type { Product } from "@/lib/types/inventory";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import type { Submit } from "./WorkspaceClient";
import { panel, type View } from "./WorkspaceViews";

import { BalanceChange } from "./OperatorDashboard";

export type Draft = { sku: string; quantity: string; note: string };
export const emptyDraft: Draft = { sku: "", quantity: "", note: "" };

export function ProductPicker({ value, onChange, read, inputRef, error }: {
  value: string; onChange: (sku: string) => void; read: Read;
  inputRef: RefObject<HTMLInputElement | null>; error?: string;
}) {
  const [focused, setFocused] = useState(false);
  const [term, setTerm] = useState(value.trim());
  useEffect(() => {
    const timer = setTimeout(() => setTerm(value.trim()), 220);
    return () => clearTimeout(timer);
  }, [value]);
  const load = useCallback(() => workspacePage<Product>(read, `/products?limit=6&search=${encodeURIComponent(term)}`)
    .then((page) => ({ term, items: page.items })), [read, term]);
  const results = useWarehouseQuery(load, focused && !!term);
  const options = results.data?.term === value.trim() && !results.error && !results.loading ? results.data.items : [];

  return <div className="min-w-0 space-y-2" onFocus={() => setFocused(true)} onBlur={(event) => {
    if (!event.relatedTarget || !event.currentTarget.contains(event.relatedTarget)) setFocused(false);
  }}>
    <Field name="SKU">
      <input ref={inputRef} className={field} value={value} onChange={(event) => onChange(event.target.value)}
        maxLength={100} autoComplete="off" placeholder="Search by product name or SKU"
        aria-invalid={!!error} aria-describedby={error ? "product-error" : undefined} />
    </Field>
    {error && <p id="product-error" className="text-sm text-rose-800">{error}</p>}
    {focused && options.length > 0 && !(options.length === 1 && options[0].sku === value) && (
      <ul aria-label="Matching products" className="divide-y divide-slate-100 rounded-lg border border-slate-200">
        {options.map((item) => <li key={item.sku}>
          <button type="button" onClick={() => { onChange(item.sku); inputRef.current?.focus(); setFocused(false); }}
            className="min-h-11 w-full px-3 py-2 text-left text-sm hover:bg-teal-50">
            <span className="block break-words font-semibold">{item.name}</span><span className="break-all font-mono text-xs">{item.sku}</span>
          </button>
        </li>)}
      </ul>
    )}
  </div>;
}

export function StockForm({ receive = false, view, draft, saveDraft, onSubmit, onSupplier, disabled, quantityRef }: {
  receive?: boolean; view: View; draft: Draft; saveDraft: (draft: Draft) => void; onSubmit: Submit;
  disabled: boolean; onSupplier?: (sku: string) => void; quantityRef?: RefObject<HTMLInputElement | null>;
}) {
  const title = receive ? "Receive stock" : "Reserve stock";
  const skuRef = useRef<HTMLInputElement>(null);
  const localQuantityRef = useRef<HTMLInputElement>(null);
  const amountRef = quantityRef ?? localQuantityRef;
  const noteRef = useRef<HTMLInputElement>(null);
  const [errors, setErrors] = useState<Partial<Draft>>({});
  const [review, setReview] = useState<{ name: string; sku: string; quantity: number; onHand: number; note: string }>();
  const sku = draft.sku.trim();
  const units = parseQuantity(draft.quantity.trim());
  const { read, revision } = view;
  const load = useCallback(() => exactProduct(read, sku), [read, sku]);
  const product = useWarehouseQuery(load, isSku(sku));
  const selected = product.data?.sku === sku ? product.data : null;
  const exceeds = selected && units !== null && units > selected.available && !receive;
  const refresh = product.refresh;
  useEffect(() => { if (revision) void refresh(); }, [revision, refresh]);

  function change(key: keyof Draft, value: string) {
    saveDraft({ ...draft, [key]: value });
    setErrors((previous) => ({ ...previous, [key]: undefined }));
  }

  function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const note = draft.note.trim();
    const problems: Partial<Draft> = {};
    if (!selected || product.loading || product.error) {
      problems.sku = product.error ? "Refresh the product balance and try again." :
        product.loading && isSku(sku) ? "Wait for the warehouse to confirm this SKU." : "Choose a product or enter its exact SKU.";
    }
    if (units === null) problems.quantity = "Enter a whole number from 1 to 2,147,483,647.";
    else if (receive && selected && selected.onHand + units > 2147483647) problems.quantity = "The resulting on-hand balance would be too large.";
    if (exceeds) problems.quantity = "Update the quantity to continue.";
    if (receive && (!note || /[\u0000-\u001f\u007f-\u009f]/u.test(note))) problems.note = "Enter a receiving reference with printable characters.";
    setErrors(problems);
    if (problems.sku) skuRef.current?.focus();
    else if (problems.quantity) amountRef.current?.focus();
    else if (problems.note) noteRef.current?.focus();
    else if (selected && units !== null) {
      if (receive) setReview({ name: selected.name, sku, quantity: units, onHand: selected.onHand, note });
      else void onSubmit({ path: "/requests", body: { sku, quantity: units }, label: "Reservation" });
    }
  }

  return <>
    <form className={`${panel} ${receive ? "max-w-2xl border-teal-200" : ""}`} onSubmit={send} noValidate>
      <div><h2 className="text-xl font-semibold text-slate-950">{title}</h2>
        {receive && <p className="mt-1 text-sm text-slate-600">Add a delivery to the warehouse’s on-hand balance.</p>}</div>
      <ProductPicker value={draft.sku} onChange={(value) => change("sku", value)} read={read} inputRef={skuRef} error={errors.sku} />
      {selected && <div className="space-y-2 rounded-lg bg-slate-50 p-3 text-sm">
        <p className="break-words font-semibold">{selected.name}</p>
        <Availability units={selected.available} /><BalanceChange key={sku} available={selected.available} />
        <p>{selected.onHand} on hand · {selected.reserved} reserved</p>
        <Freshness {...product} />
      </div>}
      {isSku(sku) && product.error && <Warning>{product.error} <button type="button" className={textAction} onClick={refresh}>Refresh product</button></Warning>}
      <Field name="Quantity"><input ref={amountRef} name="quantity" className={field} inputMode="numeric" maxLength={16}
        value={draft.quantity} onChange={(event) => change("quantity", event.target.value)}
        aria-describedby={errors.quantity ? "quantity-error" : exceeds ? "available-warning" : undefined} aria-invalid={!!errors.quantity || !!exceeds} /></Field>
      {errors.quantity && <p id="quantity-error" className="text-sm text-rose-800">{errors.quantity}</p>}
      {exceeds && <div id="available-warning" className="rounded-lg bg-amber-50 p-3 text-sm text-amber-950">
        <p role="status">Only {formatUnits(selected.available)} {selected.available === 1 ? "is" : "are"} available. Update the quantity to continue.</p>
        {onSupplier && <button type="button" className={textAction} onClick={() => onSupplier(sku)}>Check supplier</button>}
      </div>}
      {receive && <>
        <Field name="Receiving reference"><input ref={noteRef} className={field} value={draft.note}
          onChange={(event) => change("note", event.target.value)} maxLength={160}
          aria-describedby={errors.note ? "note-error" : undefined} aria-invalid={!!errors.note} /></Field>
        {errors.note && <p id="note-error" className="text-sm text-rose-800">{errors.note}</p>}
      </>}
      <button className={receive ? secondaryAction : primaryAction} type="submit" disabled={disabled || !!exceeds}>{receive ? "Review receipt" : title}</button>
    </form>
    {review && !disabled && <ConfirmAction title="Review receiving"
      description={`Add ${formatUnits(review.quantity)} of ${review.name} (${review.sku}) to the warehouse? Last checked on hand was ${review.onHand}. The movement will record “${review.note}”.`}
      action="Confirm receipt" onClose={() => setReview(undefined)} onConfirm={() => {
        setReview(undefined);
        void onSubmit({ path: "/receiving", body: { sku: review.sku, quantity: review.quantity, note: review.note }, label: "Receiving" });
      }} />}
  </>;
}
