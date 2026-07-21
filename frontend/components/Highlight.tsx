"use client";

/**
 * Marks every occurrence of a search term inside a string (MoM §3: "matched text should be
 * highlighted in yellow — searching 008 should highlight every occurrence of 008").
 *
 * Filtering a list tells you which rows matched; it does not tell you WHY, and on a wide grid the
 * matching characters can sit in any of a dozen columns. Marking them turns a filtered list into a
 * readable answer.
 *
 * Every occurrence, not just the first — a BO number can contain the same run of digits twice, and
 * marking only the first would quietly imply the second is not a match.
 */

export function Highlight({ text, q }: { text: string; q: string }) {
  const needle = (q || "").trim();
  if (!needle) return <>{text}</>;

  const hay = text ?? "";
  const lowerHay = hay.toLowerCase();
  const lowerNeedle = needle.toLowerCase();

  const parts: React.ReactNode[] = [];
  let from = 0;
  let at = lowerHay.indexOf(lowerNeedle);
  if (at < 0) return <>{hay}</>;

  let k = 0;
  while (at >= 0) {
    if (at > from) parts.push(hay.slice(from, at));
    parts.push(
      <mark key={k++} className="rounded-[2px] bg-amber-300/85 px-[1px] text-obsidian-950">
        {hay.slice(at, at + needle.length)}
      </mark>,
    );
    from = at + needle.length;
    at = lowerHay.indexOf(lowerNeedle, from);
  }
  if (from < hay.length) parts.push(hay.slice(from));
  return <>{parts}</>;
}
