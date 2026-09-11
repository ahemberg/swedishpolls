import type { JSX } from "react";
import type { Translate } from "./bootstrap";

/**
 * What the chart's marks mean, said once beneath it: the band, the reference rings, the display
 * step, the boundary rule and the gap rule.
 */

interface Props {
  readonly t: Translate;
  readonly intervalLevel: string;
  readonly step: string;
  readonly loading: boolean;
  readonly failed: boolean;
  readonly pollDots?: boolean;
}

function TimelineNotes({
  t,
  intervalLevel,
  step,
  loading,
  failed,
  pollDots = false,
}: Props): JSX.Element {
  return (
    <div>
      <p className="footnote">{t("timeline.hint")}</p>
      <p className="footnote">{t("timeline.bandNote", { level: intervalLevel })}</p>
      <p className="footnote">{t("timeline.electionDot")}</p>
      {pollDots && <p className="footnote">{t("timeline.pollDots")}</p>}
      <p className="footnote">{t("timeline.sampling", { step })}</p>
      <p className="footnote">{t("timeline.boundary")}</p>
      <p className="footnote">{t("timeline.gap")}</p>
      {loading && <p className="footnote">{t("timeline.loading")}</p>}
      {failed && <p className="footnote">{t("timeline.failed")}</p>}
    </div>
  );
}

export { TimelineNotes };
