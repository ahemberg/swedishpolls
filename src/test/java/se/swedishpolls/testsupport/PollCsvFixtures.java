package se.swedishpolls.testsupport;

/** The smallest source CSV fixtures, shared by tests that borrow rows from one another. */
public final class PollCsvFixtures {
  public static final String HEADER =
      "PublYearMonth,Company,M,L,C,KD,S,V,MP,SD,FI,Uncertain,n,PublDate,house,collectPeriodFrom,collectPeriodTo,approxPeriod\n";
  public static final String ROW =
      "2020-01,Ipsos,20.123,5,8,5,30,8,5,17,1,10,1000,2020-01-20,Ipsos,2020-01-01,2020-01-19,FALSE\n";
  public static final String SEGMENT_ROW =
      ROW.replace("2020-01-20", "2016-06-20")
          .replace("2020-01-01", "2016-06-01")
          .replace("2020-01-19", "2016-06-19");

  private PollCsvFixtures() {}

  public static byte[] csv(String rows) {
    return (HEADER + rows).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
