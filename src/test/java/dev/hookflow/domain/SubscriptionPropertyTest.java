package dev.hookflow.domain;

import dev.hookflow.domain.endpoint.Endpoint;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subscription matching decides who receives what. A property is the right tool
 * here because the rule is short but the input space (lists of types, order,
 * duplicates, whitespace, wildcard) is not.
 *
 * <p>Generated type names deliberately exclude commas and whitespace: those are
 * the <b>separators</b> of the stored format, so a "type name" containing them is
 * not a type name. The separator behaviour is covered by dedicated examples.
 */
class SubscriptionPropertyTest {

    private static final String WILDCARD = "*";

    @Property
    void exactlyTheDeclaredTypesMatch(
            @ForAll("typeNameLists") List<String> declared,
            @ForAll("typeNames") String queried) {

        Endpoint endpoint = endpoint(String.join(",", declared));

        if (declared.contains(WILDCARD)) {
            assertThat(endpoint.subscribesTo(queried)).isTrue();
        } else {
            assertThat(endpoint.subscribesTo(queried)).isEqualTo(declared.contains(queried));
        }
    }

    @Property
    void wildcardMatchesEverything(@ForAll("typeNames") String eventType) {
        assertThat(endpoint(WILDCARD).subscribesTo(eventType)).isTrue();
        assertThat(endpoint(" * ").subscribesTo(eventType)).isTrue();
        assertThat(endpoint("*,order.paid").subscribesTo(eventType)).isTrue();
    }

    @Property
    void surroundingWhitespaceIsIgnored(@ForAll("typeNames") String eventType) {
        assertThat(endpoint("  " + eventType + "  ").subscribesTo(eventType)).isTrue();
        assertThat(endpoint("other.type , " + eventType).subscribesTo(eventType)).isTrue();
        assertThat(endpoint(eventType + "\t,\n other.type").subscribesTo(eventType)).isTrue();
    }

    @Property
    void matchingIsExactNotPrefixBased(@ForAll("typeNames") String eventType) {
        Endpoint endpoint = endpoint(eventType);
        assertThat(endpoint.subscribesTo(eventType + ".suffix")).isFalse();
        if (!eventType.isEmpty()) {
            assertThat(endpoint.subscribesTo(eventType.substring(0, eventType.length() - 1))).isFalse();
        }
    }

    @Property
    void blankDeclarationsMatchNothing(@ForAll("typeNames") String eventType) {
        assertThat(endpoint("").subscribesTo(eventType)).isFalse();
        assertThat(endpoint("   ").subscribesTo(eventType)).isFalse();
        assertThat(endpoint(null).subscribesTo(eventType)).isFalse();
        assertThat(endpoint(",,").subscribesTo(eventType)).isFalse();
    }

    @Property
    void duplicateDeclarationsDoNotChangeSemantics(@ForAll("typeNames") String eventType) {
        assertThat(endpoint(eventType + "," + eventType + "," + eventType).subscribesTo(eventType)).isTrue();
    }

    @Property
    void caseIsSignificant(@ForAll("typeNames") String eventType) {
        String upper = eventType.toUpperCase(java.util.Locale.ROOT);
        if (!upper.equals(eventType)) {
            assertThat(endpoint(eventType).subscribesTo(upper)).isFalse();
        }
    }

    /** Type names never contain the separators ',' or whitespace, nor the wildcard. */
    @Provide
    Arbitrary<String> typeNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('.', '_', '-')
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(value -> !value.equals(WILDCARD));
    }

    @Provide
    Arbitrary<List<String>> typeNameLists() {
        return typeNames().list().ofMaxSize(5);
    }

    private static Endpoint endpoint(String subscribedTypes) {
        return new Endpoint(1L, 1L, "https://example.test/hook", new byte[]{1}, (short) 1,
                subscribedTypes, 8, Endpoint.ACTIVE, null, null);
    }
}
