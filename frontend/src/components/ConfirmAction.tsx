"use client";

import { useEffect, useRef } from "react";
import { destructiveAction, primaryAction, secondaryAction } from "./Workspace";

export function ConfirmAction({ title, description, action, destructive = false, onClose, onConfirm }: {
  title: string; description: string; action: string; destructive?: boolean;
  onClose: () => void; onConfirm: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const back = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const node = dialog.current;
    const previous = document.activeElement;
    node?.showModal();
    back.current?.focus();
    return () => {
      node?.close();
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, []);

  return <dialog ref={dialog} aria-labelledby="confirm-title" aria-describedby="confirm-description"
    onCancel={(event) => { event.preventDefault(); onClose(); }}
    className="confirm-dialog m-auto w-[calc(100%-2rem)] max-w-md rounded-xl border border-slate-200 bg-white p-6 text-slate-950 shadow-xl backdrop:bg-slate-950/60">
    <h2 id="confirm-title" className="text-xl font-semibold">{title}</h2>
    <p id="confirm-description" className="mt-3 text-sm text-slate-700">{description}</p>
    <div className="mt-6 flex flex-wrap justify-end gap-2">
      <button ref={back} type="button" className={secondaryAction} onClick={onClose}>Back</button>
      <button type="button" className={destructive ? destructiveAction : primaryAction} onClick={onConfirm}>{action}</button>
    </div>
  </dialog>;
}
