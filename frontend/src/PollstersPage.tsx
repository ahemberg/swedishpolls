import { Fragment, type JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { pollstersData } from "./bootstrap";
import { decimal } from "./format";
import type { InstituteMeta, PollstersCell, PollstersData, PollstersMatrix } from "./institutes";
import { componentName } from "./labels";

/**
 * The pollsters page.
 *
 * The institutes and their method eras first, then one heat table per election cycle with the
 * interval table beside it. Both renderings read the same cells the server rendered, so a number
 * shown in colour and the same number shown as a row cannot disagree.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

/** The institutes and their house effects, read from the bootstrap the server resolved. */
function pollsters(page: Bootstrap): PollstersData | undefined {
  return pollstersData(page)?.pollsters;
}

function date(
  institute: InstituteMeta,
  which: "firstCollection" | "lastCollection",
  t: Translate,
): string {
  const value = institute[which];
  if (value === null) {
    return t("polls.missing");
  }
  return value;
}

function companies(institute: InstituteMeta): string {
  return institute.companies.join(", ");
}

function Era({
  id,
  evidence,
}: {
  readonly id: string;
  readonly evidence: string | null;
}): JSX.Element {
  if (evidence === null || evidence === "") {
    return <span>{id}</span>;
  }
  return <a href={evidence}>{id}</a>;
}

function Institutes({
  data,
  t,
}: {
  readonly data: PollstersData;
  readonly t: Translate;
}): JSX.Element {
  return (
    <section className="sec">
      <h2>{t("pollsters.metadata")}</h2>
      <div className="scroll">
        <table className="institutes">
          <thead>
            <tr>
              <th scope="col">{t("polls.column.institute")}</th>
              <th scope="col">{t("pollsters.column.companies")}</th>
              <th scope="col">{t("pollsters.column.eras")}</th>
              <th scope="col" className="num">
                {t("pollsters.column.polls")}
              </th>
              <th scope="col">{t("pollsters.column.first")}</th>
              <th scope="col">{t("pollsters.column.last")}</th>
            </tr>
          </thead>
          <tbody>
            {data.institutes.map((institute) => (
              <tr key={institute.institute}>
                <th scope="row">{institute.institute}</th>
                <td>{companies(institute)}</td>
                <td>
                  {institute.methodEras.map((era, index) => (
                    <Fragment key={era.id}>
                      {index > 0 && ", "}
                      <Era id={era.id} evidence={era.evidence} />
                    </Fragment>
                  ))}
                </td>
                <td className="num">{String(institute.polls)}</td>
                <td>{date(institute, "firstCollection", t)}</td>
                <td>{date(institute, "lastCollection", t)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="footnote">{t("pollsters.erasNote")}</p>
    </section>
  );
}

function cellByComponent(
  row: PollstersMatrix["rows"][number],
): Readonly<Record<string, PollstersCell>> {
  const cells: Record<string, PollstersCell> = {};
  for (const cell of row.cells) {
    cells[cell.component] = cell;
  }
  return cells;
}

function range(page: Bootstrap, cell: PollstersCell, t: Translate): string {
  return t("party.effectRange", {
    lower: decimal(cell.lower, page.language),
    upper: decimal(cell.upper, page.language),
  });
}

function effect(cell: PollstersCell, page: Bootstrap, t: Translate): string {
  const value = decimal(cell.mean, page.language);
  if (!cell.shrunk) {
    return value;
  }
  return t("pollsters.shrunkCell", { effect: value });
}

function HeatCell({
  page,
  cell,
  t,
}: {
  readonly page: Bootstrap;
  readonly cell: PollstersCell;
  readonly t: Translate;
}): JSX.Element {
  return (
    <td className={`num heat ${cell.heat}`} title={range(page, cell, t)}>
      {decimal(cell.mean, page.language)}
    </td>
  );
}

function HeatGrid({
  page,
  data,
  matrix,
  t,
}: {
  readonly page: Bootstrap;
  readonly data: PollstersData;
  readonly matrix: PollstersMatrix;
  readonly t: Translate;
}): JSX.Element {
  return (
    <div className="scroll">
      <table className="heat-grid">
        <caption>{t("pollsters.gridCaption", { cycle: matrix.cycle })}</caption>
        <thead>
          <tr>
            <th scope="col">{t("polls.column.institute")}</th>
            {data.components.map((component) => (
              <th scope="col" className="num" key={component}>
                {component}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {matrix.rows.map((row) => {
            const cells = cellByComponent(row);
            return (
              <tr key={row.institute}>
                <th scope="row">{row.institute}</th>
                {data.components.map((component) => {
                  const cell = cells[component];
                  if (cell === undefined) {
                    return <td className="num" key={component} />;
                  }
                  return <HeatCell cell={cell} page={page} t={t} key={component} />;
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function EffectTable({
  page,
  matrix,
  t,
}: {
  readonly page: Bootstrap;
  readonly matrix: PollstersMatrix;
  readonly t: Translate;
}): JSX.Element {
  return (
    <div className="scroll">
      <table className="heat-table">
        <caption>{t("pollsters.intervalCaption")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("polls.column.institute")}</th>
            <th scope="col">{t("estimate.column.party")}</th>
            <th scope="col" className="num">
              {t("party.houseEffect")}
            </th>
            <th scope="col" className="num">
              {t("party.houseInterval")}
            </th>
          </tr>
        </thead>
        <tbody>
          {matrix.rows.flatMap((row) =>
            row.cells.map((cell) => (
              <tr key={`${row.institute}-${cell.component}`}>
                <th scope="row">{row.institute}</th>
                <td>{componentName(page, cell.component)}</td>
                <td className="num">{effect(cell, page, t)}</td>
                <td className="num">{range(page, cell, t)}</td>
              </tr>
            )),
          )}
        </tbody>
      </table>
    </div>
  );
}

function anyShrunk(data: PollstersData): boolean {
  return data.matrices.some((matrix) =>
    matrix.rows.some((row) => row.cells.some((cell) => cell.shrunk)),
  );
}

function Effects({
  page,
  data,
  t,
}: {
  readonly page: Bootstrap;
  readonly data: PollstersData;
  readonly t: Translate;
}): JSX.Element {
  if (data.matrices.length === 0) {
    return (
      <section className="sec">
        <h2>{t("party.houseEffects")}</h2>
        <p>{t("party.houseUnavailable")}</p>
      </section>
    );
  }
  const cycles: JSX.Element[] = [];
  for (const matrix of data.matrices) {
    cycles.push(
      <div key={matrix.cycle}>
        <HeatGrid page={page} data={data} matrix={matrix} t={t} />
        <EffectTable page={page} matrix={matrix} t={t} />
      </div>,
    );
  }
  return (
    <section className="sec">
      <h2>{t("party.houseEffects")}</h2>
      {cycles}
      <p className="footnote">{data.reference}</p>
      {anyShrunk(data) && <p className="footnote">{t("pollsters.shrunkNote")}</p>}
    </section>
  );
}

function Downloads({ page, t }: Props): JSX.Element | null {
  const { api } = page;
  if (api === undefined) {
    return null;
  }
  const pin = `?publication=${api.publication}&language=${api.language}`;
  return (
    <section className="sec">
      <h2>{t("downloads.title")}</h2>
      <ul className="downloads">
        <li>
          <a className="btn" href={`${api.base}/institutes${pin}`}>
            {t("downloads.houseEffects")}
          </a>
        </li>
      </ul>
      <p className="meta">{t("downloads.pinned", { publication: api.publication })}</p>
    </section>
  );
}

function PollstersPage({ page, t }: Props): JSX.Element | null {
  const data = pollsters(page);
  if (data === undefined) {
    return null;
  }
  return (
    <div>
      <h1>{t("head.title.pollsters")}</h1>
      <p className="meta">{t("pollsters.lead")}</p>
      <p className="meta">{t("notForecast")}</p>
      <Institutes data={data} t={t} />
      <Effects page={page} data={data} t={t} />
      <Downloads page={page} t={t} />
    </div>
  );
}

export { PollstersPage };
