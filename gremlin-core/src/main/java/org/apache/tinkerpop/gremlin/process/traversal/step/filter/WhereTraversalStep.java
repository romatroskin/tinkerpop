/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *
 */

package org.apache.tinkerpop.gremlin.process.traversal.step.filter;

import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Scoping;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.StartStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.ConjunctionStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalUtil;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class WhereTraversalStep<S> extends FilterStep<S> implements TraversalParent, Scoping {

    private static final Set<TraverserRequirement> LOCAL_REQUIREMENTS = EnumSet.of(TraverserRequirement.OBJECT, TraverserRequirement.SIDE_EFFECTS);
    private static final Set<TraverserRequirement> GLOBAL_REQUIREMENTS = EnumSet.of(TraverserRequirement.PATH, TraverserRequirement.SIDE_EFFECTS);

    protected Traversal.Admin<?, ?> whereTraversal;
    protected Scope scope;
    protected final Set<String> scopeKeys = new HashSet<>();

    public WhereTraversalStep(final Traversal.Admin traversal, final Scope scope, final Traversal<?, ?> whereTraversal) {
        super(traversal);
        this.scope = scope;
        this.whereTraversal = whereTraversal.asAdmin();
        this.configureStartAndEndSteps(this.whereTraversal);
        if (this.scopeKeys.isEmpty())
            throw new IllegalArgumentException("A where()-traversal must have at least a start or end label (i.e. variable): " + whereTraversal);
        this.whereTraversal = this.integrateChild(this.whereTraversal);
    }

    private void configureStartAndEndSteps(final Traversal.Admin<?, ?> whereTraversal) {
        ConjunctionStrategy.instance().apply(whereTraversal);
        //// START STEP to WhereStartStep
        final Step<?, ?> startStep = whereTraversal.getStartStep();
        if (startStep instanceof ConjunctionStep || startStep instanceof NotStep) {       // for conjunction- and not-steps
            ((TraversalParent) startStep).getLocalChildren().forEach(this::configureStartAndEndSteps);
        } else if (StartStep.isVariableStartStep(startStep)) {  // as("a").out()... traversals
            final String label = startStep.getLabels().iterator().next();
            this.scopeKeys.add(label);
            TraversalHelper.replaceStep(startStep, new WhereStartStep(whereTraversal, label), whereTraversal);
        } else if (!whereTraversal.getEndStep().getLabels().isEmpty()) {                    // ...out().as("a") traversals
            TraversalHelper.insertBeforeStep(new WhereStartStep(whereTraversal, null), (Step) startStep, whereTraversal);
        }
        //// END STEP to WhereEndStep
        final Step<?, ?> endStep = whereTraversal.getEndStep();
        if (!endStep.getLabels().isEmpty()) {
            if (endStep.getLabels().size() > 1)
                throw new IllegalArgumentException("The end step of a where()-traversal can only have one label: " + endStep);
            final String label = endStep.getLabels().iterator().next();
            this.scopeKeys.add(label);
            endStep.removeLabel(label);
            whereTraversal.addStep(new WhereEndStep(whereTraversal, label));
        }
    }


    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        return TraversalUtil.test((Traverser.Admin) traverser, this.whereTraversal);
    }

    @Override
    public List<Traversal.Admin<?, ?>> getLocalChildren() {
        return null == this.whereTraversal ? Collections.emptyList() : Collections.singletonList(this.whereTraversal);
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.scope, this.whereTraversal);
    }

    @Override
    public Set<String> getScopeKeys() {
        return Collections.unmodifiableSet(this.scopeKeys);
    }

    @Override
    public WhereTraversalStep<S> clone() {
        final WhereTraversalStep<S> clone = (WhereTraversalStep<S>) super.clone();
        clone.whereTraversal = clone.integrateChild(this.whereTraversal.clone());
        return clone;
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ this.scope.hashCode() ^ this.whereTraversal.hashCode();
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return Scope.local == this.scope ? LOCAL_REQUIREMENTS : GLOBAL_REQUIREMENTS;
    }

    @Override
    public void setScope(final Scope scope) {
        this.scope = scope;
    }

    @Override
    public Scope getScope() {
        return this.scope;
    }

    @Override
    public Scope recommendNextScope() {
        return this.scope;
    }

    //////////////////////////////

    public static class WhereStartStep<S> extends MapStep<S, Object> implements Scoping {

        private String selectKey;
        private Scope scope = Scope.global;

        public WhereStartStep(final Traversal.Admin traversal, final String selectKey) {
            super(traversal);
            this.selectKey = selectKey;
        }

        @Override
        protected Object map(final Traverser.Admin<S> traverser) {
            if (this.getTraversal().getEndStep() instanceof WhereEndStep)
                ((WhereEndStep) this.getTraversal().getEndStep()).processStartTraverser(traverser);
            return null == this.selectKey ? traverser.get() : this.getScopeValueByKey(Pop.last, this.selectKey, traverser);
        }

        @Override
        public String toString() {
            return StringFactory.stepString(this, this.scope, this.selectKey);
        }

        @Override
        public int hashCode() {
            return super.hashCode() ^ this.scope.hashCode() ^ (null == this.selectKey ? "null".hashCode() : this.selectKey.hashCode());
        }

        @Override
        public Scope getScope() {
            return this.scope;
        }

        @Override
        public Scope recommendNextScope() {
            return this.scope;
        }

        @Override
        public void setScope(Scope scope) {
            this.scope = scope;
        }

        public void removeScopeKey() {
            this.selectKey = null;
        }

        @Override
        public Set<String> getScopeKeys() {
            return null == this.selectKey ? Collections.emptySet() : Collections.singleton(this.selectKey);
        }
    }

    public static class WhereEndStep extends FilterStep<Object> implements Scoping {

        private final String matchKey;
        private Object matchValue = null;
        private Scope scope = Scope.global;

        public WhereEndStep(final Traversal.Admin traversal, final String matchKey) {
            super(traversal);
            this.matchKey = matchKey;
        }

        public void processStartTraverser(final Traverser.Admin traverser) {
            if (null != this.matchKey)
                this.matchValue = this.getScopeValueByKey(Pop.last, this.matchKey, traverser);
        }

        @Override
        protected boolean filter(final Traverser.Admin<Object> traverser) {
            return null == this.matchKey || traverser.get().equals(this.matchValue);
        }

        @Override
        public String toString() {
            return StringFactory.stepString(this, this.scope, this.matchKey);
        }

        @Override
        public int hashCode() {
            return super.hashCode() ^ this.scope.hashCode() ^ (null == this.matchKey ? "null".hashCode() : this.matchKey.hashCode());
        }

        @Override
        public Scope getScope() {
            return this.scope;
        }

        @Override
        public Scope recommendNextScope() {
            return this.scope;
        }

        @Override
        public void setScope(Scope scope) {
            // this.scope = scope;
        }

        @Override
        public Set<String> getScopeKeys() {
            return null == this.matchKey ? Collections.emptySet() : Collections.singleton(this.matchKey);
        }
    }


    //////////////////////////////
}