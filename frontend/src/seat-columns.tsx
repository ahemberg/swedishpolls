import type { JSX } from "react";
import type { Translate } from "./bootstrap";

/**
 * The three seat columns a party row and a coalition row both carry.
 *
 * They are one heading group because they are one contract: an integer allocation, the posterior
 * mean over the model's draws, and the interval around it. Wherever seats are tabulated, those
 * three stay distinct and stay in this order, so a reader never has to work out which column is
 * the allocation and which is the average of one.
 */

/** The heading cells. A caller adds the columns that identify the row on either side. */
function SeatColumns({ t }: { readonly t: Translate }): JSX.Element {
  return (
    <>
      <th scope="col" className="num">
        {t("seats.column.point")}
      </th>
      <th scope="col" className="num">
        {t("seats.column.mean")}
      </th>
      <th scope="col" className="num">
        {t("seats.column.interval")}
      </th>
    </>
  );
}

/** A seat interval, as the two integers the publication carries rather than a derived range. */
function seatSpan(bounds: readonly [number, number]): string {
  return `${bounds[0]}–${bounds[1]}`;
}

export { SeatColumns, seatSpan };
