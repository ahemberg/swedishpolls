import type { JSX } from "react";
import type { Bootstrap, Poll, Polls, Translate } from "./bootstrap";
import { count, decimal, shortDate } from "./format";
import { PollColumns } from "./poll-columns";

/**
 * The most recently published polls of the pinned snapshot.
 *
 * A share the source did not report stays missing. Nothing here fills a blank with a zero or with a
 * neighbouring institute's figure.
 */

interface Props {
  readonly page: Bootstrap;
  readonly polls: Polls;
  readonly t: Translate;
}

function fieldwork(poll: Poll, page: Bootstrap, t: Translate): string {
  if (poll.collectionFrom === null || poll.collectionTo === null) {
    return t("polls.missing");
  }
  const from = shortDate(poll.collectionFrom, page.locale);
  const to = shortDate(poll.collectionTo, page.locale);
  if (poll.approximatePeriod) {
    return `${from} - ${to} (${t("polls.approximate")})`;
  }
  return `${from} - ${to}`;
}

function sample(poll: Poll, page: Bootstrap, t: Translate): string {
  if (poll.sampleSize === null) {
    return t("polls.missing");
  }
  return count(poll.sampleSize, page.locale);
}

function share(poll: Poll, component: string, page: Bootstrap, t: Translate): string {
  const value = poll.shares[component];
  if (value === undefined || value === null) {
    return t("polls.missing");
  }
  return decimal(value, page.language);
}

function LatestPolls({ page, polls, t }: Props): JSX.Element {
  const components = Object.keys(polls.labels);
  return (
    <section className="sec o-polls">
      <h2>{t("polls.title")}</h2>
      <div className="scroll">
        <table>
          <caption>{t("polls.caption")}</caption>
          <thead>
            <tr>
              <PollColumns t={t} />
              {components.map((component) => (
                <th scope="col" className="num" key={component} title={polls.labels[component]}>
                  {component}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {polls.polls.map((poll) => (
              <tr key={poll.pollId}>
                <th scope="row">{poll.institute}</th>
                <td>{fieldwork(poll, page, t)}</td>
                <td className="num">{sample(poll, page, t)}</td>
                {components.map((component) => (
                  <td className="num" key={component}>
                    {share(poll, component, page, t)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export { LatestPolls };
