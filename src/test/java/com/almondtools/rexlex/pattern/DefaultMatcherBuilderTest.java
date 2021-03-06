package com.almondtools.rexlex.pattern;

import static com.almondtools.rexlex.automaton.GenericAutomatonBuilder.match;
import static com.almondtools.rexlex.automaton.GenericAutomatonBuilder.matchUnlimitedLoop;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class DefaultMatcherBuilderTest {

	private DefaultMatcherBuilder builder;

	@Before
	public void before() {
		builder = DefaultMatcherBuilder.from(matchUnlimitedLoop(match('a'), 1));
	}

	@Test
	public void testMatches() throws Exception {
		assertTrue(builder.buildMatcher("a").matches());
		assertFalse(builder.buildMatcher("b").matches());
		assertFalse(builder.buildMatcher("").matches());
	}

	@Test
	public void testFind() throws Exception {
		assertTrue(builder.buildFinder("babb").find());
		assertFalse(builder.buildFinder("bbbb").find());
		assertFalse(builder.buildFinder("").find());
	}

	@Test
	public void testStartEndGroupOnMatch() throws Exception {
		Finder matcher = builder.buildFinder("baabb");
		assertTrue(matcher.find());
		assertThat(matcher.start(), equalTo(1l));
		assertThat(matcher.end(), equalTo(3l));
		assertThat(matcher.group(), equalTo("aa"));
	}

	@Test
	public void testStartEndGroupOnMissmatch() throws Exception {
		Finder matcher = builder.buildFinder("bbb");
		assertFalse(matcher.find());
		assertThat(matcher.start(), equalTo(-1l));
		assertThat(matcher.end(), equalTo(-1l));
		assertThat(matcher.group(), nullValue());
	}

}
