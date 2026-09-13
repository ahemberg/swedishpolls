package se.swedishpolls.web.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.Translations;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.web.PollFilters;

/** One filtered page from a publication's pinned source snapshot. */
record PollsResponse(
    PublicationIdentityResponse publication,
    Filters filters,
    String coveragePeriod,
    int total,
    int page,
    int pageSize,
    String csv,
    Map<String, String> labels,
    List<Poll> polls) {
  static PollsResponse from(
      PublicationHeader header,
      PollQuery.Result result,
      PollQuery.Filters filters,
      String publicationId,
      Translations text) {
    final Map<String, String> labels = new LinkedHashMap<>();
    for (final String component : filters.selectedComponents()) {
      labels.put(component, text.component(component));
    }
    return new PollsResponse(
        new PublicationIdentityResponse(
            header.publicationId(), header.runId(), header.snapshotId()),
        new Filters(
            filters.from(),
            filters.to(),
            filters.institutes(),
            filters.selectedComponents(),
            filters.includeExcluded(),
            filters.coveragePeriod()),
        filters.coveragePeriod() == null ? header.headlinePeriod() : filters.coveragePeriod(),
        result.total(),
        result.page1(),
        result.pageSize(),
        PollFilters.csvLink(publicationId, filters),
        Collections.unmodifiableMap(labels),
        result.page().stream().map(row -> Poll.from(row, filters)).toList());
  }

  record Filters(
      LocalDate from,
      LocalDate to,
      List<String> institute,
      List<String> party,
      boolean includeExcluded,
      String coveragePeriod) {}

  record Poll(
      String pollId,
      String institute,
      String company,
      String methodEra,
      String methodEvidence,
      String surveyType,
      LocalDate publicationDate,
      LocalDate collectionFrom,
      LocalDate collectionTo,
      boolean approximatePeriod,
      BigDecimal sampleSize,
      String denominatorNote,
      Map<String, BigDecimal> shares,
      BigDecimal other,
      BigDecimal uncertain,
      String coveragePeriod,
      boolean eligible,
      List<String> exclusionReasons) {
    static Poll from(PollQuery.Row row, PollQuery.Filters filters) {
      final PollCsv.Poll poll = row.poll();
      final Map<String, BigDecimal> shares = new LinkedHashMap<>();
      for (final String component : filters.selectedComponents()) {
        shares.put(component, poll.shares().get(component));
      }
      return new Poll(
          row.pollId(),
          poll.institute(),
          poll.company(),
          poll.methodEra(),
          poll.methodEvidence(),
          poll.surveyType(),
          poll.publicationDate(),
          poll.collectionFrom(),
          poll.collectionTo(),
          row.approximatePeriod(),
          poll.sampleSize(),
          poll.denominatorNote(),
          Collections.unmodifiableMap(shares),
          poll.remainder(),
          row.uncertain(),
          row.coveragePeriod(),
          poll.eligible(),
          poll.exclusionReasons());
    }
  }
}
