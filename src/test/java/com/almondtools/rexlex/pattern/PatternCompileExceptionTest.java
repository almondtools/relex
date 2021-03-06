package com.almondtools.rexlex.pattern;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import org.junit.Test;


public class PatternCompileExceptionTest {

	@Test
	public void testOrdinaryPatternCompileException() throws Exception {
		PatternCompileException exception = new PatternCompileException("pattern", 1, "bc");
		assertThat(exception.getMessage(), equalTo("error compiling pattern <pattern> at position 1, found: a, expecting on of: bc"));
	}

}
