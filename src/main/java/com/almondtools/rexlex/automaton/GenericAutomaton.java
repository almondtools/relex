package com.almondtools.rexlex.automaton;

import static com.almondtools.rexlex.automaton.AutomatonProperty.ACYCLIC;
import static com.almondtools.rexlex.automaton.AutomatonProperty.CYCLIC;
import static com.almondtools.rexlex.automaton.AutomatonProperty.LINEAR;
import static com.almondtools.rexlex.automaton.AutomatonProperty.UNKNOWN;
import static net.amygdalum.util.text.CharUtils.after;
import static net.amygdalum.util.text.CharUtils.before;
import static net.amygdalum.util.text.CharUtils.charToString;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import com.almondtools.rexlex.Token;
import com.almondtools.rexlex.TokenFactory;
import com.almondtools.rexlex.TokenType;
import com.almondtools.rexlex.pattern.DefaultTokenType;
import com.almondtools.rexlex.pattern.DefaultTokenTypeFactory;
import com.almondtools.rexlex.pattern.TokenIterator;
import com.almondtools.rexlex.pattern.TokenTypeFactory;

import net.amygdalum.util.builders.Lists;
import net.amygdalum.util.builders.Sets;
import net.amygdalum.util.builders.Iterators;
import net.amygdalum.util.builders.Predicate;
import net.amygdalum.util.builders.Predicates;
import net.amygdalum.util.io.CharProvider;
import net.amygdalum.util.text.CharUtils;
import net.amygdalum.util.worklist.WorkSet;

public class GenericAutomaton implements Automaton, Cloneable {

	private TokenTypeFactory tokenTypes;
	private State start;
	private State error;

	private AutomatonProperty property;

	public GenericAutomaton() {
		this(new DefaultTokenTypeFactory());
	}

	public GenericAutomaton(State start) {
		this(start, new DefaultTokenTypeFactory());
	}

	public GenericAutomaton(TokenTypeFactory tokenTypes) {
		this.tokenTypes = tokenTypes;
	}

	public GenericAutomaton(State start, TokenTypeFactory tokenTypes) {
		this.start = start;
		this.tokenTypes = tokenTypes;
		this.property = UNKNOWN;
	}

	@Override
	public String getId() {
		if (start == null) {
			return "null";
		} else {
			return start.getId();
		}
	}

	@Override
	public TokenType getErrorType() {
		if (error != null) {
			return error.getType();
		} else {
			return tokenTypes.errorType();
		}
	}

	void setStart(State start) {
		this.start = start;
	}

	public State getStart() {
		return start;
	}

	public State getError() {
		return error;
	}

	public TokenTypeFactory getTokenTypes() {
		return tokenTypes;
	}

	@Override
	public AutomatonProperty getProperty() {
		if (property.isUnknown()) {
			property = computeProperties();
		}
		return property;
	}

	private AutomatonProperty computeProperties() {
		GenericAutomaton a = clone().totalize().determinize();
		Set<State> visited = new HashSet<State>();
		List<State> nexts = new LinkedList<State>();
		nexts.add(a.start);
		AutomatonProperty property = LINEAR;
		loop: while (!nexts.isEmpty()) {
			State next = nexts.remove(0);
			visited.add(next);

			List<EventTransition> transitions = next.getNextClosure();
			int validTargets = 0;
			for (EventTransition transition : transitions) {
				State target = transition.getTarget();
				if (!target.error()) {
					validTargets += transition.getTo() - transition.getFrom() + 1;
					if (validTargets > 1) {
						property = ACYCLIC;
					}
					if (visited.contains(target)) {
						if (findPath(target, next) != null) {
							property = CYCLIC;
							break loop;
						}
					} else {
						nexts.add(target);
					}
				}
			}
		}
		return property;
	}

	private String findPath(State from, State to) {
		Map<State, String> paths = new IdentityHashMap<State, String>();
		paths.put(from, "");
		List<State> nexts = new LinkedList<State>();
		nexts.add(from);
		while (!nexts.isEmpty()) {
			State next = nexts.remove(0);
			if (next == to) {
				return paths.get(next);
			}

			List<EventTransition> transitions = next.getNextClosure();
			for (EventTransition transition : transitions) {
				State target = transition.getTarget();
				if (!paths.containsKey(target)) {
					String path = paths.get(next) + transition.getFrom();
					paths.put(target, path);
					nexts.add(target);
				}
			}
		}
		return null;
	}

	@Override
	public Iterable<String> getSamples(int limit) {
		return start.apply(new SampleBuilder(limit));
	}

	public boolean fails() {
		return start.error() || (start.getTransitions().isEmpty() && !start.accept());
	}

	public boolean accepts() {
		return start.accept();
	}

	public GenericAutomaton addInitialSelfLoop() {
		GenericAutomaton nfa = clone();
		State start = nfa.getStart();
		start.addTransition(new RangeTransition(Character.MIN_VALUE, Character.MAX_VALUE, start));
		return nfa;
	}

	public GenericAutomaton totalize() {
		TokenType errorType = tokenTypes.errorType();
		Set<State> live = findLiveStates();
		error = new State(errorType);
		for (State state : live) {
			state.addErrorTransitions(error);
		}
		return this;
	}

	public GenericAutomaton totalize(TokenTypeFactory tokenTypes) {
		this.tokenTypes = tokenTypes;
		return totalize();
	}

	public <T extends Automaton> T toAutomaton(ToAutomaton<GenericAutomaton, T> builder) {
		return builder.transform(this);
	}

	GenericAutomaton eliminateEpsilons() {
		Set<State> visited = new HashSet<State>();
		List<State> todo = new LinkedList<State>();
		todo.add(start);
		while (!todo.isEmpty()) {
			State current = todo.remove(0);
			for (Transition transition : current.getTransitions()) {
				State target = transition.getTarget();
				if (!visited.contains(target) && !todo.contains(target)) {
					todo.add(target);
				}
			}
			boolean changed = current.inlineEpsilons(tokenTypes);
			if (changed) {
				todo.remove(current);
				todo.add(current);
			} else {
				visited.add(current);
			}
		}
		return this;
	}

	GenericAutomaton eliminateDuplicateFinalStates() {
		Set<State> allStates = start.findReachableStates();
		Map<TokenType, State> newtargets = new HashMap<TokenType, State>();
		for (State state : allStates) {
			for (Transition transition : state.getTransitions()) {
				State target = transition.getTarget();
				if (target.getTransitions().isEmpty()) {
					TokenType type = target.getType();
					State newtarget = newtargets.get(type);
					if (newtarget == null) {
						newtarget = new State(type);
						newtargets.put(type, newtarget);
					}
					transition.setTarget(newtarget);
				}
			}
		}
		return this;
	}

	GenericAutomaton eliminateDuplicateTransitions() {
		for (State state : start.findReachableStates()) {
			state.eliminateDuplicateTransitions();
		}
		return this;
	}

	/**
	 * generates a deterministic (yet not minimized) Automaton expects this automaton to be already epsilon-free and condition-free
	 */
	GenericAutomaton determinize() {
		char[] relevant = computeRelevantCharacters();
		Set<State> start = Sets.of(this.start);

		Set<Set<State>> visited = new HashSet<Set<State>>();
		LinkedList<Set<State>> todo = new LinkedList<Set<State>>();
		Map<Set<State>, State> combinedState = new HashMap<Set<State>, State>();

		visited.add(start);
		todo.add(start);
		combinedState.put(start, new State());

		while (todo.size() > 0) {
			Set<State> current = todo.removeFirst();
			State newCurrent = computeJointState(combinedState, current);
			for (int i = 0; i < relevant.length; i++) {
				char currentChar = relevant[i];
				char beforeNextChar = i + 1 < relevant.length ? before(relevant[i + 1]) : Character.MAX_VALUE;
				Set<State> possibleNext = new HashSet<State>();
				for (State possibleCurrent : current) {
					for (Transition next : possibleCurrent.nexts(currentChar)) {
						possibleNext.add(next.getTarget());
					}
				}
				if (!visited.contains(possibleNext)) {
					visited.add(possibleNext);
					todo.add(possibleNext);
					combinedState.put(possibleNext, new State());
				}
				State newNext = combinedState.get(possibleNext);
				if (currentChar == beforeNextChar) {
					newCurrent.addTransition(new ExactTransition(currentChar, newNext));
				} else {
					newCurrent.addTransition(new RangeTransition(currentChar, beforeNextChar, newNext));
				}
			}
		}
		return new GenericAutomaton(combinedState.get(start));
	}

	GenericAutomaton totalizeAndClean() {
		return totalizeAndClean(DefaultTokenType.ERROR);
	}

	GenericAutomaton totalizeAndClean(TokenType remainder) {
		Set<State> live = findLiveStates();
		error = new State(remainder);
		if (live.size() == 0) {
			start = error;
		}
		for (State state : live) {
			state.mergeAdjacentTransitions();
			Iterator<Transition> transitionIterator = state.getTransitions().iterator();
			while (transitionIterator.hasNext()) {
				Transition transition = transitionIterator.next();
				if (!live.contains(transition.getTarget())) {
					transitionIterator.remove();
				}
			}
			state.addErrorTransitions(error);
		}
		return this;
	}

	/**
	 * expects dead state free, total DFA
	 */
	GenericAutomaton minimize() {
		char[] relevant = computeRelevantCharacters();
		Set<State> allStates = findAllStates();
		Set<State> acceptStates = findAcceptStates();
		Set<Set<State>> partitionedAcceptStates = splitByTokenType(acceptStates);
		Set<State> innerStates = Sets.hashed(allStates).minus(acceptStates).build();

		Set<Set<State>> partititions = initPartitions(innerStates, partitionedAcceptStates);
		Map<State, List<EventTransition>> reverseTransitions = computeReverseTransitions(allStates);
		LinkedList<Set<State>> worklist = new LinkedList<Set<State>>();
		worklist.addAll(partitionedAcceptStates);
		while (!worklist.isEmpty()) {
			Set<State> item = worklist.removeFirst();
			for (char c : relevant) {
				Set<State> X = validOrigins(c, item, reverseTransitions);
				List<Set<State>> partitionsSnapshot = new ArrayList<Set<State>>(partititions);
				for (Set<State> Y : partitionsSnapshot) {
					Set<State> iXY = Sets.intersectionOf(Y, X);
					Set<State> cXY = Sets.complementOf(Y, X);
					if (!iXY.isEmpty() && !cXY.isEmpty()) {
						refine(partititions, Y, iXY, cXY);
						if (worklist.contains(Y)) {
							refine(worklist, Y, iXY, cXY);
						} else {
							if (iXY.size() <= cXY.size()) {
								worklist.add(iXY);
							} else {
								worklist.add(cXY);
							}
						}
					}
				}
			}
		}
		Map<State, State> newStates = buildNewStates(partititions);
		start = newStates.get(start);
		error = newStates.get(error);
		return this;
	}

	private Set<Set<State>> initPartitions(Set<State> nonaccept, Set<Set<State>> accept) {
		return Sets.hashed(accept).addConditional(!nonaccept.isEmpty(), nonaccept).build();
	}

	private Set<Set<State>> splitByTokenType(Set<State> states) {
		Map<TokenType, Set<State>> split = new HashMap<TokenType, Set<State>>();
		for (State state : states) {
			TokenType key = state.getType();
			Set<State> set = split.get(key);
			if (set == null) {
				set = new HashSet<State>();
				split.put(key, set);
			}
			set.add(state);
		}
		return Sets.hashed(split.values()).build();
	}

	private Map<State, List<EventTransition>> computeReverseTransitions(Set<State> states) {
		Map<State, List<EventTransition>> reverse = new IdentityHashMap<State, List<EventTransition>>();
		for (State state : states) {
			for (EventTransition transition : state.getEventTransitions()) {
				State target = transition.getTarget();
				List<EventTransition> reverseTransitions = reverse.get(target);
				if (reverseTransitions == null) {
					reverseTransitions = new LinkedList<EventTransition>();
					reverse.put(target, reverseTransitions);
				}
				EventTransition reverseTransition = (EventTransition) transition.clone(state);
				reverseTransitions.add(reverseTransition);
			}
		}
		return reverse;
	}

	private void refine(Collection<Set<State>> collection, Set<State> Y, Set<State> iXY, Set<State> cXY) {
		collection.remove(Y);
		collection.add(iXY);
		collection.add(cXY);
	}

	private Set<State> validOrigins(char ch, Set<State> targets, Map<State, List<EventTransition>> reverse) {
		Set<State> states = new HashSet<State>();
		for (State target : targets) {
			List<EventTransition> reverseTransitions = reverse.get(target);
			if (reverseTransitions == null) {
				continue;
			}
			for (EventTransition transition : reverseTransitions) {
				if (transition.matches(ch)) {
					states.add(transition.getTarget());
				}
			}
		}
		return states;
	}

	private Map<State, State> buildNewStates(Set<Set<State>> partitions) {
		Map<State, State> newStates = new IdentityHashMap<State, State>();
		for (Set<State> partition : partitions) {
			State firstElement = partition.iterator().next();
			State newState = new State(firstElement.getType());
			for (State state : partition) {
				newStates.put(state, newState);
			}
		}
		for (Map.Entry<State, State> entry : newStates.entrySet()) {
			State state = entry.getKey();
			State newState = entry.getValue();
			for (EventTransition transition : state.getEventTransitions()) {
				State target = transition.getTarget();
				State newTarget = newStates.get(target);
				if (newState.nexts(transition.getFrom()).isEmpty()) {
					Transition cloned = transition.clone(newTarget);
					newState.addTransition(cloned);
				}
			}
		}
		return newStates;
	}

	@Override
	public GenericAutomaton revert() {
		Revert revert = new Revert(start).apply();
		State newstart = new State();
		for (State state : findAcceptStates()) {
			State newstate = revert.resolve(state);
			TokenType newtype = revert.getType(newstate);
			State newend = new State(newtype);
			newstate = new Replace(newstate).replace(revert.resolve(start), newend).apply().replaced();
			newstart.addTransition(new EpsilonTransition(newstate));
		}
		return new GenericAutomaton(newstart);
	}

	private State computeJointState(Map<Set<State>, State> newstate, Set<State> current) {
		State newCurrent = newstate.get(current);
		newCurrent.setType(jointTypeOf(current));
		return newCurrent;
	}

	private TokenType jointTypeOf(Set<State> current) {
		TokenType type = null;
		for (State part : current) {
			TokenType newtype = part.getType();
			if (type == null) {
				type = newtype;
			} else if (newtype == null) {
				//keep type
			} else if (type.error()) {
				type = newtype;
			} else if (newtype.error()) {
				//keep type;
			} else {
				type = tokenTypes.union(newtype, type);
			}
		}
		return type;
	}

	char[] computeRelevantCharacters() {
		SortedSet<Character> relevant = new TreeSet<Character>();
		for (State state : findAllStates()) {
			relevant.addAll(state.getRelevantCharacters());
		}
		return CharUtils.fromWrapped(relevant);
	}

	Set<State> findAcceptStates() {
		return start.findAcceptStates();
	}

	Set<State> findLiveStates() {
		return start.findLiveStates();
	}

	Set<State> findAllStates() {
		Set<State> allStates = start.findReachableStates();
		if (error != null) {
			allStates.add(error);
		}
		return allStates;
	}

	Set<State> findDeadStates() {
		return Sets.hashed(findAllStates()).minus(findLiveStates()).build();
	}

	@Override
	public AutomatonMatcher matcher() {
		return new Matcher();
	}

	@Override
	public <T extends Token> Iterator<T> tokenize(CharProvider chars, TokenFactory<T> factory) {
		return new TokenIterator<T>(this, chars, factory);
	}

	@Override
	public GenericAutomatonExport store(String name) {
		return new GenericAutomatonExport(this, name);
	}

	@Override
	public GenericAutomaton clone() {
		try {
			GenericAutomaton clone = (GenericAutomaton) super.clone();
			clone.start = this.start.cloneTree();
			clone.error = this.error == null ? null : this.error.clone();
			return clone;
		} catch (CloneNotSupportedException e) {
			return null;
		}
	}

	public static interface StateVisitor<T> {

		T visitState(State state);

	}

	public static class State implements Cloneable {

		private TokenType type;
		private List<Transition> transitions;
		private List<State> closure;
		private List<EventTransition> nextclosure;

		public State() {
			this.transitions = new ArrayList<Transition>();
		}

		public State(TokenType type) {
			this.type = type;
			this.transitions = new ArrayList<Transition>();
		}

		public boolean accept() {
			return type != null && type.accept();
		}

		public boolean error() {
			return type != null && type.error();
		}

		public boolean acceptClosure() {
			if (accept()) {
				return true;
			} else {
				for (State state : getClosure()) {
					if (state.accept()) {
						return true;
					}
				}
				return false;
			}
		}

		public String getId() {
			return String.valueOf(System.identityHashCode(this));
		}

		public void setType(TokenType type) {
			this.type = type;
		}

		public TokenType getType() {
			return type;
		}

		public void addTransition(Transition transition) {
			if (!transitions.contains(transition)) {
				this.transitions.add(transition);
				clearCaches();
			}
		}

		public int removeTransition(Transition transition) {
			int pos = transitions.indexOf(transition);
			transitions.remove(pos);
			while (pos > -1) {
				clearCaches();
				pos = transitions.indexOf(transition);
			}
			return pos;
		}

		public void addTransitions(List<Transition> transitions) {
			for (Transition transition : transitions) {
				addTransition(transition);
			}
		}

		public void replaceTransition(Transition transition, Transition replacetransition) {
			int pos = transitions.indexOf(transition);
			while (pos > -1) {
				transitions.remove(pos);
				transitions.add(pos, replacetransition);
				clearCaches();
				pos = transitions.indexOf(transition);
			}
		}

		public void replaceTransition(Transition transition, List<Transition> replacetransitions) {
			int pos = transitions.indexOf(transition);
			while (pos > -1) {
				transitions.remove(pos);
				transitions.addAll(pos, replacetransitions);
				clearCaches();
				pos = transitions.indexOf(transition);
			}
		}

		public List<Transition> getTransitions() {
			return transitions;
		}

		public void setTransitions(List<Transition> transitions) {
			this.transitions = transitions;
			clearCaches();
		}

		SortedSet<EventTransition> computeSortedEventTransitions() {
			SortedSet<EventTransition> sortedTransitions = new TreeSet<EventTransition>(new Comparator<EventTransition>() {

				@Override
				public int compare(EventTransition o1, EventTransition o2) {
					int result = o1.getFrom() - o2.getFrom();
					if (result == 0) {
						result = o1.getTo() - o2.getTo();
					}
					return result;
				}
			});
			sortedTransitions.addAll(getEventTransitions());
			return sortedTransitions;
		}

		List<EventTransition> getEventTransitions() {
			List<EventTransition> eventTransitions = new ArrayList<EventTransition>();
			for (Transition transition : transitions) {
				if (transition instanceof EventTransition) {
					eventTransitions.add((EventTransition) transition);
				}
			}
			return eventTransitions;
		}

		public boolean inlineEpsilons(TokenTypeFactory tokenTypes) {
			boolean changed = false;
			for (int i = 0; i < transitions.size(); i++) {
				Transition transition = transitions.get(i);
				if (transition instanceof EpsilonTransition) {
					State target = transition.getTarget();
					type = tokenTypes.union(target.getType(), type);
					transitions.remove(i);
					for (Transition t : target.getTransitions()) {
						if (!transitions.contains(t)) {
							transitions.add(i, t);
							i++;
						}
					}
					i--;
					changed = true;
				}
			}
			if (changed) {
				clearCaches();
			}
			return changed;
		}

		public void eliminateDuplicateTransitions() {
			int oldsize = transitions.size();
			Set<Transition> found = new HashSet<Transition>();
			Iterator<Transition> transitionIterator = transitions.iterator();
			while (transitionIterator.hasNext()) {
				Transition transition = transitionIterator.next();
				boolean success = found.add(transition);
				if (!success) {
					transitionIterator.remove();
				}
			}
			if (oldsize != transitions.size()) {
				clearCaches();
			}
		}

		public void eliminateErrorTransitions() {
			int oldsize = transitions.size();
			Iterator<Transition> transitionIterator = transitions.iterator();
			while (transitionIterator.hasNext()) {
				Transition transition = transitionIterator.next();
				if (transition.getTarget().error()) {
					transitionIterator.remove();
				}
			}
			if (oldsize != transitions.size()) {
				clearCaches();
			}
		}

		void mergeAdjacentTransitions() {
			SortedSet<EventTransition> sortedTransitions = computeSortedEventTransitions();
			LinkedList<EventTransition> mergedTransitions = new LinkedList<EventTransition>();

			for (EventTransition transition : sortedTransitions) {
				if (mergedTransitions.isEmpty()) {
					mergedTransitions.add(transition);
				} else {
					EventTransition last = mergedTransitions.getLast();
					char lastFrom = last.getFrom();
					char lastTo = last.getTo();
					char nextFrom = transition.getFrom();
					char nextTo = transition.getTo();
					if (lastTo + 1 >= nextFrom && last.getTarget() == transition.getTarget()) {
						mergedTransitions.removeLast();
						char from = lastFrom < nextFrom ? lastFrom : nextFrom;
						char to = lastTo < nextTo ? nextTo : lastTo;
						mergedTransitions.add(new RangeTransition(from, to, last.getTarget()));
					} else {
						mergedTransitions.add(transition);
					}
				}
			}
			this.transitions = new ArrayList<Transition>(mergedTransitions);
		}

		public SortedSet<Character> getRelevantCharacters() {
			SortedSet<Character> relevant = new TreeSet<Character>();
			relevant.add(Character.MIN_VALUE);
			for (EventTransition transition : getSortedNextClosure()) {
				char from = ((EventTransition) transition).getFrom();
				relevant.add(from);

				char to = ((EventTransition) transition).getTo();
				if (to < Character.MAX_VALUE) {
					relevant.add(after(to));
				}
			}
			return relevant;
		}

		private void clearCaches() {
			this.closure = null;
			this.nextclosure = null;
		}

		public List<State> getClosure() {
			if (closure == null) {
				closure = computeClosure();
			}
			return closure;
		}

		private List<State> computeClosure() {
			Set<State> closure = new LinkedHashSet<State>();
			List<State> todo = Lists.of(this);
			while (!todo.isEmpty()) {
				State state = todo.remove(0);
				if (!closure.contains(state)) {
					closure.add(state);
					for (Transition transition : state.getTransitions()) {
						if (transition instanceof EpsilonTransition) {
							todo.add(transition.getTarget());
						}
					}
				}
			}
			return new ArrayList<State>(closure);
		}

		public List<EventTransition> getNextClosure() {
			if (nextclosure == null) {
				nextclosure = computeNextClosure();
			}
			return nextclosure;
		}

		private List<EventTransition> computeNextClosure() {
			List<EventTransition> nexts = new ArrayList<EventTransition>();
			for (State state : getClosure()) {
				for (Transition transition : state.getTransitions()) {
					if (transition instanceof EventTransition) {
						nexts.add((EventTransition) transition);
					}
				}
			}
			return nexts;
		}

		public Set<TokenType> getTypeClosure() {
			Set<TokenType> types = new HashSet<TokenType>();
			for (State state : getClosure()) {
				TokenType stateType = state.getType();
				if (stateType != null) {
					types.add(stateType);
				}
			}
			return types;
		}

		public List<EventTransition> getSortedNextClosure() {
			List<EventTransition> closure = new ArrayList<EventTransition>(getNextClosure());
			Collections.sort(closure, new TransitionComparator());
			return closure;
		}

		public Set<State> getConnectedStates() {
			Set<State> reachable = new LinkedHashSet<State>();
			for (Transition transition : transitions) {
				reachable.add(transition.getTarget());
			}
			return reachable;
		}

		public List<EventTransition> nexts(char ch) {
			List<EventTransition> nexts = new LinkedList<EventTransition>();
			for (EventTransition transition : getNextClosure()) {
				if (transition.matches(ch)) {
					nexts.add(transition);
				}
			}
			return nexts;
		}

		@Override
		public State clone() {
			try {
				State clone = (State) super.clone();
				clone.type = type;
				clone.transitions = new ArrayList<Transition>(transitions.size());
				return clone;
			} catch (CloneNotSupportedException e) {
				return null;
			}
		}

		public <T> T apply(StateVisitor<T> visitor) {
			return visitor.visitState(this);
		}

		@Override
		public String toString() {
			StringBuilder buffer = new StringBuilder("state(").append(getId()).append(")\n");
			for (Transition transition : transitions) {
				buffer.append('\t').append(transition.toString()).append('\n');
			}
			return buffer.toString();
		}

		public void addErrorTransitions(State error) {
			char min = Character.MAX_VALUE;
			char max = Character.MIN_VALUE;

			char current = Character.MIN_VALUE;
			for (EventTransition transition : getSortedNextClosure()) {
				char from = transition.getFrom();
				if (from < min) {
					min = from;
				}
				char to = transition.getTo();
				if (to > max) {
					max = to;
				}

				if (from == current + 1) {
					transitions.add(new ExactTransition(current, error));
				} else if (from > current) {
					transitions.add(new RangeTransition(current, before(from), error));
				}
				current = after(to);
			}
			if (max == Character.MAX_VALUE) {
				// MAX_VALUE already handled
			} else if (current == Character.MAX_VALUE) {
				transitions.add(new ExactTransition(Character.MAX_VALUE, error));
			} else {// current < Character.MAX_VALUE
				transitions.add(new RangeTransition(current, Character.MAX_VALUE, error));
			}
			clearCaches();
		}

		public State cloneTree() {
			return new Clone(this).apply().start();
		}

		public Set<State> findAcceptStates() {
			return new FindStates(this, new Predicate<State>() {
				@Override
				public boolean evaluate(State object) {
					return object.accept();
				}
			}).apply().states();
		}

		public Set<State> findReachableStates() {
			return new FindStates(this).apply().states();
		}

		public Set<State> findLiveStates() {
			Set<State> states = findReachableStates();
			Map<State, Set<State>> map = new HashMap<State, Set<State>>();
			for (State s : states) {
				map.put(s, new HashSet<State>());
			}
			for (State s : states) {
				for (Transition transition : s.getTransitions()) {
					map.get(transition.getTarget()).add(s);
				}
			}
			Set<State> live = new HashSet<State>(findAcceptStates());
			LinkedList<State> worklist = new LinkedList<State>(live);
			while (!worklist.isEmpty()) {
				State current = worklist.removeFirst();
				for (State previous : map.get(current)) {
					if (!live.contains(previous)) {
						live.add(previous);
						worklist.add(previous);
					}
				}
			}
			return live;
		}

	}

	static class TransitionComparator implements Comparator<Transition> {

		@Override
		public int compare(Transition t1, Transition t2) {
			if (t1 instanceof EventTransition && t2 instanceof EventTransition) {
				return compare((EventTransition) t1, (EventTransition) t2);
			} else if (t1 instanceof EventTransition) {
				return 1;
			} else if (t2 instanceof EventTransition) {
				return -1;
			} else {
				return System.identityHashCode(t1) - System.identityHashCode(t2);
			}
		}

		private int compare(EventTransition t1, EventTransition t2) {
			int result = t1.getFrom() - t2.getFrom();
			if (result == 0) {
				result = t1.getTo() - t2.getTo();
			}
			if (result == 0) {
				return System.identityHashCode(t1) - System.identityHashCode(t2);
			} else {
				return result;
			}
		}

	}

	public static interface Transition extends Cloneable {

		State getTarget();

		void setTarget(State state);

		Transition clone();

		Transition clone(State target);

	}

	public static interface EventlessTransition extends Transition {

	}

	public static interface EventTransition extends Transition {

		char getFrom();

		char getTo();

		boolean matches(char ch);

	}

	static abstract class BasicTransition implements Transition {

		private State target;

		public BasicTransition(State target) {
			this.target = target;
		}

		@Override
		public State getTarget() {
			return target;
		}

		@Override
		public void setTarget(State target) {
			this.target = target;
		}

		@Override
		public BasicTransition clone() {
			try {
				BasicTransition clone = (BasicTransition) super.clone();
				clone.target = target;
				return clone;
			} catch (CloneNotSupportedException e) {
				return null;
			}
		}

		@Override
		public BasicTransition clone(State target) {
			BasicTransition clone = clone();
			clone.target = target;
			return clone;
		}

		@Override
		public int hashCode() {
			return 31 * target.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			BasicTransition other = (BasicTransition) obj;
			return this.target == other.target;
		}

	}

	static class EpsilonTransition extends BasicTransition implements EventlessTransition {

		public EpsilonTransition(State target) {
			super(target);
		}

		@Override
		public String toString() {
			return new StringBuilder().append(" -> ").append(getTarget().getId()).toString();
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj);
		}

	}

	static class RangeTransition extends BasicTransition implements EventTransition {

		private char from;
		private char to;

		public RangeTransition(char from, char to, State target) {
			super(target);
			this.from = from;
			this.to = to;
		}

		@Override
		public char getFrom() {
			return from;
		}

		@Override
		public char getTo() {
			return to;
		}

		@Override
		public boolean matches(char ch) {
			return ch >= from && ch <= to;
		}

		@Override
		public String toString() {
			return new StringBuilder().append(" -<").append(charToString(from)).append("..").append(charToString(to)).append(">-> ").append(getTarget().getId()).toString();
		}

		@Override
		public int hashCode() {
			return super.hashCode() + 13 * Character.valueOf(from).hashCode() + 31 * Character.valueOf(to);
		}

		@Override
		public boolean equals(Object obj) {
			if (!super.equals(obj)) {
				return false;
			}
			RangeTransition other = (RangeTransition) obj;
			return this.from == other.from && this.to == other.to;
		}

		@Override
		public RangeTransition clone() {
			RangeTransition transition = (RangeTransition) super.clone();
			transition.from = from;
			transition.to = to;
			return transition;
		}

	}

	static class ExactTransition extends BasicTransition implements EventTransition {

		private char value;

		public ExactTransition(char value, State target) {
			super(target);
			this.value = value;
		}

		public char getValue() {
			return value;
		}

		@Override
		public char getFrom() {
			return value;
		}

		@Override
		public char getTo() {
			return value;
		}

		@Override
		public boolean matches(char ch) {
			return value == ch;
		}

		@Override
		public String toString() {
			return new StringBuilder().append(" -<").append(charToString(value)).append(">-> ").append(getTarget().getId()).toString();
		}

		@Override
		public int hashCode() {
			return super.hashCode() + 17 * Character.valueOf(value);
		}

		@Override
		public boolean equals(Object obj) {
			if (!super.equals(obj)) {
				return false;
			}
			ExactTransition other = (ExactTransition) obj;
			return this.value == other.value;
		}

		@Override
		public ExactTransition clone() {
			ExactTransition transition = (ExactTransition) super.clone();
			transition.value = value;
			return transition;
		}

	}

	public static abstract class TreeWalker<T extends TreeWalker<?>> {

		private WorkSet<State> todo;

		public TreeWalker(State start) {
			this.todo = new WorkSet<>();
			todo.add(start);
		}

		public void next(State state) {
			todo.add(state);
		}

		public void next(Collection<State> states) {
			todo.addAll(states);
		}

		public abstract void apply(State state);

		public abstract T self();

		public T apply() {
			while (!todo.isEmpty()) {
				apply(todo.remove());
			}
			return self();
		}

	}

	/**
	 * finds all accepting states in an automaton
	 * 
	 * @readonly (no modifications)
	 */
	static class FindStates extends TreeWalker<FindStates> {

		private Predicate<State> predicate;
		private Set<State> accepted;

		public FindStates(State start) {
			this(start, Predicates.all(State.class));
		}

		public FindStates(State start, Predicate<State> predicate) {
			super(start);
			this.predicate = predicate;
			this.accepted = new LinkedHashSet<State>();
		}

		@Override
		public FindStates self() {
			return this;
		}

		@Override
		public void apply(State state) {
			if (predicate.evaluate(state)) {
				accepted.add(state);
			}
			next(state.getConnectedStates());
		}

		public Set<State> states() {
			return accepted;
		}

	}

	/**
	 * Clones the states
	 * 
	 * @conservative (operations do not modify the given automaton, but produce a new one)
	 */
	static class Clone extends TreeWalker<Clone> {

		private State start;
		private Map<State, State> states;

		public Clone(State start) {
			super(start);
			this.start = start;
			this.states = new IdentityHashMap<State, State>();
		}

		@Override
		public Clone self() {
			return this;
		}

		private State cloned(State state) {
			State clonedstate = states.get(state);
			if (clonedstate == null) {
				clonedstate = state.clone();
				states.put(state, clonedstate);
			}
			return clonedstate;
		}

		@Override
		public void apply(State state) {
			State clonedstate = cloned(state);
			for (Transition transition : state.getTransitions()) {
				State target = transition.getTarget();
				State clonedTarget = cloned(target);
				Transition clonedtransition = transition.clone(clonedTarget);
				clonedstate.addTransition(clonedtransition);
				next(target);
			}
		}

		public State start() {
			return states.get(start);
		}

	}

	/**
	 * Replaces the transitions targeting given states by a clone transition containing targeting the replacement
	 * 
	 * @invasive (operations modify the visited Automaton)
	 */
	static class Replace extends TreeWalker<Replace> {

		private State start;
		private Map<State, State> states;

		public Replace(State start) {
			super(start);
			this.start = start;
			this.states = new IdentityHashMap<State, State>();
		}

		public Replace replace(State from, State to) {
			this.states.put(from, to);
			return this;
		}

		@Override
		public Replace self() {
			return this;
		}

		@Override
		public void apply(State state) {
			State replacingState = replaced(state);
			if (states.get(replacingState) == null) {
				states.put(replacingState, replacingState);
				List<Transition> replacingTransitions = new ArrayList<Transition>();
				for (Transition transition : state.getTransitions()) {
					State target = transition.getTarget();
					State replacingTarget = replaced(target);
					if (replacingTarget != target) {
						replacingTransitions.add(transition.clone(replacingTarget));
					} else {
						replacingTransitions.add(transition);
					}
					next(target);
				}
				replacingState.setTransitions(replacingTransitions);
			}
		}

		private State replaced(State state) {
			State replacingState = states.get(state);
			if (replacingState == null) {
				replacingState = state;
			}
			return replacingState;
		}

		public State replaced() {
			return states.get(start);
		}

	}

	/**
	 * Reverts the direction of the transitions and sets the correct final tokentype
	 * 
	 * @conservative (operations do not modify the given automaton, but produce a new one)
	 */
	static class Revert extends TreeWalker<Revert> {

		private Map<State, TokenType> types;
		private Map<State, State> states;

		public Revert(State start) {
			super(start);
			this.states = new IdentityHashMap<State, State>();
			this.types = new IdentityHashMap<State, TokenType>();
		}

		@Override
		public Revert self() {
			return this;
		}

		public State resolve(State state) {
			return states.get(state);
		}

		public TokenType getType(State state) {
			return types.get(state);
		}

		@Override
		public void apply(State state) {
			State revertedState = reverted(state);
			for (Transition transition : state.getTransitions()) {
				State target = transition.getTarget();
				State revertedTarget = reverted(target);
				Transition newtransition = transition.clone(revertedState);
				revertedTarget.addTransition(newtransition);
				next(target);
			}
		}

		private State reverted(State state) {
			State revertedState = states.get(state);
			if (revertedState == null) {
				revertedState = state.clone();
				revertedState.setType(null);
				TokenType type = state.getType();
				if (type != null) {
					types.put(revertedState, type);
				}
				states.put(state, revertedState);
			}
			return revertedState;
		}

	}

	class Matcher implements AutomatonMatcher {

		private AutomatonMatcherListener listener;

		private CharProvider chars;
		private Deque<MatchContext> contexts;

		public Matcher() {
		}

		@Override
		public Matcher withListener(AutomatonMatcherListener listener) {
			this.listener = listener;
			return this;
		}

		public MatchContext initialContext(CharProvider chars) {
			return newContext(Iterators.of(start), chars);
		}

		public MatchContext newContext(Iterator<State> nextStates, CharProvider chars) {
			return new MatchContext(nextStates, chars.current());
		}

		public Iterator<State> nextStates(State state, CharProvider chars) {
			return new NextStateIterator(state, chars);
		}

		@Override
		public AutomatonMatcherListener applyTo(CharProvider chars) {
			this.chars = chars;
			Deque<MatchContext> contexts = new ArrayDeque<MatchContext>();
			contexts.push(initialContext(chars));
			resume(chars, contexts);
			return listener;
		}

		@Override
		public boolean isSuspended() {
			return contexts != null && !contexts.isEmpty() && chars != null;
		}

		@Override
		public AutomatonMatcherListener resume() {
			resume(chars, contexts);
			return listener;
		}

		private void resume(CharProvider chars, Deque<MatchContext> contexts) {
			long start = contexts.getLast().pos;
			long misMatchPosition = Integer.MIN_VALUE;
			while (!contexts.isEmpty()) {
				MatchContext context = contexts.peek();
				chars.move(context.pos);
				if (context.hasMoreStates()) {
					boolean suspend = false;
					State state = context.nextState();
					if (state.acceptClosure()) {
						chars.mark();
						suspend = listener.reportMatch(chars, start, state.getTypeClosure().iterator().next());
						if (chars.changed()) {
							contexts.clear();
							contexts.add(initialContext(chars));
						}
					}
					if (state.error()) {
						misMatchPosition = chars.current() < misMatchPosition ? misMatchPosition : chars.current();
					} else if (chars.finished()) {
						//do nothing
					} else if (state.nexts(chars.lookahead()).isEmpty()) {
						misMatchPosition = chars.current() < misMatchPosition ? misMatchPosition : chars.current();
					} else {
						contexts.push(newContext(nextStates(state, chars), chars));
					}
					if (suspend) {
						this.contexts = contexts;
						return;
					}
				} else {
					contexts.pop();
					if (contexts.isEmpty() && misMatchPosition > Integer.MIN_VALUE) {
						boolean suspend = listener.recoverMismatch(chars, start);
						start = chars.current();
						misMatchPosition = Integer.MIN_VALUE;
						contexts.push(initialContext(chars));
						if (suspend) {
							this.contexts = contexts;
							return;
						}
					}
				}
			}
		}
	}

	private static class MatchContext {

		private Iterator<State> states;
		private long pos;

		public MatchContext(Iterator<State> state, long pos) {
			this.states = state;
			this.pos = pos;
		}

		public boolean hasMoreStates() {
			return states.hasNext();
		}

		public State nextState() {
			return states.next();
		}

	}

	private static class NextStateIterator implements Iterator<State> {

		private CharProvider chars;
		private Iterator<EventTransition> nexts;
		private State current;

		public NextStateIterator(State state, CharProvider chars) {
			this.chars = chars;
			this.nexts = state.nexts(chars.lookahead()).iterator();
		}

		@Override
		public boolean hasNext() {
			if (!nexts.hasNext()) {
				return false;
			} else {
				EventTransition next = nexts.next();
				current = next.getTarget();
				return true;
			}
		}

		@Override
		public State next() {
			State next = current;
			chars.next();
			current = null;
			return next;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	private static class SampleBuilder implements StateVisitor<Set<String>> {

		private String base;
		private int limit;

		public SampleBuilder(int limit) {
			this.base = "";
			this.limit = limit;
		}

		public SampleBuilder(String base, int limit) {
			this.base = base;
			this.limit = limit;
		}

		@Override
		public Set<String> visitState(State state) {
			Set<String> samples = new HashSet<String>();
			if (state.accept()) {
				int oldsize = samples.size();
				samples.add(base);
				int newsize = samples.size();
				limit -= newsize - oldsize;
			}
			for (EventTransition transition : state.getNextClosure()) {
				State target = transition.getTarget();
				for (char event = transition.getFrom(); event <= transition.getTo(); event++) {
					if (limit > 0) {
						Set<String> nextsamples = target.apply(new SampleBuilder(base + event, limit));
						int oldsize = samples.size();
						samples.addAll(nextsamples);
						int newsize = samples.size();
						limit -= newsize - oldsize;
					}
				}
			}
			return samples;
		}

	}

}
