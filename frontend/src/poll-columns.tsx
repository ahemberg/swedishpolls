import type { JSX } from "react";
import type { Translate } from "./bootstrap";

function PollColumns({ t }: { readonly t: Translate }): JSX.Element {
  return (
    <>
      <th scope="col">{t("polls.column.institute")}</th>
      <th scope="col">{t("polls.column.fieldwork")}</th>
      <th scope="col" className="num">
        {t("polls.column.sample")}
      </th>
    </>
  );
}

export { PollColumns };
