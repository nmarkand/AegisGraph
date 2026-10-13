package com.cts.aegisgraph.shadowtrace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** LLM structured-output target. Edges reference nodes via stable slug refs (e.g. "company:alpha-holdings-ltd"). */
public record ExtractedOwnership(
        List<PersonNode> persons,
        List<CompanyNode> companies,
        List<TrustNode> trusts,
        List<OwnsEdge> owns,
        List<ControlsEdge> controls) {

    public static final ExtractedOwnership EMPTY = new ExtractedOwnership(null, null, null, null, null);

    public ExtractedOwnership {
        persons = persons == null ? List.of() : List.copyOf(persons);
        companies = companies == null ? List.of() : List.copyOf(companies);
        trusts = trusts == null ? List.of() : List.copyOf(trusts);
        owns = owns == null ? List.of() : List.copyOf(owns);
        controls = controls == null ? List.of() : List.copyOf(controls);
    }

    // sanctionStatus, riskScore, passportHash are derived downstream - never LLM-extracted.
    public record PersonNode(String ref, String name, String nationality,
                             @JsonPropertyDescription("Passport/ID number exactly as printed, else null") String documentNumber,
                             @JsonPropertyDescription("True only if the text states politically exposed person status") Boolean pep) {}

    public record CompanyNode(String ref, String name,
                              @JsonPropertyDescription("Country/territory of incorporation, e.g. BVI, Panama, Switzerland") String jurisdiction,
                              String registrationNumber) {}

    public record TrustNode(String ref, String name, TrustType type,
                            @JsonPropertyDescription("ISO-8601 yyyy-MM-dd, else null") String establishmentDate,
                            String governingLaw) {}

    public record OwnsEdge(String sourceRef, String targetRef,
                           @JsonPropertyDescription("Ownership share 0-100 as a plain number without a % sign, null if not stated") Double percentage,
                           @JsonPropertyDescription("Voting rights share 0-100 as a plain number, null if not stated") Double votingRights,
                           @JsonPropertyDescription("Verbatim quote of at most 60 characters") String evidence) {

        /** Tolerant JSON entry point: models sometimes answer "100%" or "35,5" instead of a number. */
        @JsonCreator
        public static OwnsEdge fromJson(@JsonProperty("sourceRef") String sourceRef,
                                        @JsonProperty("targetRef") String targetRef,
                                        @JsonProperty("percentage") Object percentage,
                                        @JsonProperty("votingRights") Object votingRights,
                                        @JsonProperty("evidence") String evidence) {
            return new OwnsEdge(sourceRef, targetRef, toPercent(percentage), toPercent(votingRights), evidence);
        }

        private static Double toPercent(Object v) {
            if (v == null) return null;
            if (v instanceof Number n) return n.doubleValue();
            String s = v.toString().replace(',', '.').replaceAll("[^0-9.\\-]", "");
            try {
                return s.isEmpty() ? null : Double.valueOf(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public record ControlsEdge(String sourceRef, String targetRef, ControlRole role, String evidence) {}

    public enum TrustType { DISCRETIONARY, FIXED_INTEREST, FOUNDATION, OTHER }

    public enum ControlRole { DIRECTOR, NOMINEE_DIRECTOR, PROTECTOR, SETTLOR, TRUSTEE, AUTHORIZED_SIGNATORY, OTHER }

    public ExtractedOwnership merge(ExtractedOwnership o) {
        return new ExtractedOwnership(
                union(persons, o.persons, PersonNode::ref),
                union(companies, o.companies, CompanyNode::ref),
                union(trusts, o.trusts, TrustNode::ref),
                union(owns, o.owns, e -> e.sourceRef() + ">" + e.targetRef()),
                union(controls, o.controls, e -> e.sourceRef() + ">" + e.targetRef() + ">" + e.role()));
    }

    private static <T> List<T> union(List<T> a, List<T> b, Function<T, String> key) {
        return Stream.concat(a.stream(), b.stream())
                .collect(Collectors.toMap(key, x -> x, (x, y) -> x, LinkedHashMap::new))
                .values().stream().toList();
    }
}
