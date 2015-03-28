package com.almondtools.rexlex.automaton;

import com.almondtools.rexlex.TokenType;
import com.almondtools.rexlex.io.CharProvider;
import com.almondtools.rexlex.pattern.Match;

public class LongestMatchListener implements MatchListener {

	private Match match;
	private Match nextMatch;

	@Override
	public boolean reportMatch(CharProvider chars, int start, TokenType accepted) {
		if (nextMatch != null) {
			match = nextMatch;
			nextMatch = null;
		}
		int end = chars.current();
		String text = chars.slice(start, end );
		if (end < start) {
			start = end;
		}
		if (match == null || (match.start() == start && match.text().length() < text.length())) {
			match = new Match(start, text , accepted);
			return false;
		} else {
			nextMatch = new Match(start, text , accepted);
			return true;
		}
	}

	@Override
	public boolean recoverMismatch(CharProvider chars, int start) {
		return true;
	}

	@Override
	public Match getMatch() {
		Match match = this.match;
		this.match = null;
		return match;
	}
	
}