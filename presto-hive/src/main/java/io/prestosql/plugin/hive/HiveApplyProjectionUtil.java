/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.hive;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.expression.ConnectorExpression;
import io.prestosql.spi.expression.FieldDereference;
import io.prestosql.spi.expression.Variable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

final class HiveApplyProjectionUtil
{
    private HiveApplyProjectionUtil(){}

    public static List<ConnectorExpression> extractSupportedProjectedColumns(ConnectorExpression expression)
    {
        requireNonNull(expression, "expression is null");
        ImmutableList.Builder<ConnectorExpression> supportedSubExpressions = ImmutableList.builder();
        fillSupportedProjectedColumns(expression, supportedSubExpressions);
        return supportedSubExpressions.build();
    }

    private static void fillSupportedProjectedColumns(ConnectorExpression expression, ImmutableList.Builder<ConnectorExpression> supportedSubExpressions)
    {
        if (isPushDownSupported(expression)) {
            supportedSubExpressions.add(expression);
            return;
        }

        // If the whole expression is not supported, look for a partially supported projection
        if (expression instanceof FieldDereference) {
            fillSupportedProjectedColumns(((FieldDereference) expression).getTarget(), supportedSubExpressions);
        }
    }

    @VisibleForTesting
    static boolean isPushDownSupported(ConnectorExpression expression)
    {
        return expression instanceof Variable ||
            (expression instanceof FieldDereference && isPushDownSupported(((FieldDereference) expression).getTarget()));
    }

    public static ProjectedColumnRepresentation createProjectedColumnRepresentation(ConnectorExpression expression)
    {
        ImmutableList.Builder<Integer> ordinals = ImmutableList.builder();

        Variable target;
        while (true) {
            if (expression instanceof Variable) {
                target = (Variable) expression;
                break;
            }
            else if (expression instanceof FieldDereference) {
                FieldDereference dereference = (FieldDereference) expression;
                ordinals.add(dereference.getField());
                expression = dereference.getTarget();
            }
            else {
                throw new IllegalArgumentException("expression is not a valid dereference chain");
            }
        }

        return new ProjectedColumnRepresentation(target, ordinals.build().reverse());
    }

    /**
     * Replace all connector expressions with variables as given by {@param expressionToVariableMappings} in a top down manner.
     * i.e. if the replacement occurs for the parent, the children will not be visited.
     */
    public static ConnectorExpression replaceWithNewVariables(ConnectorExpression expression, Map<ConnectorExpression, Variable> expressionToVariableMappings)
    {
        if (expressionToVariableMappings.containsKey(expression)) {
            return expressionToVariableMappings.get(expression);
        }

        if (expression instanceof FieldDereference) {
            ConnectorExpression newTarget = replaceWithNewVariables(((FieldDereference) expression).getTarget(), expressionToVariableMappings);
            return new FieldDereference(expression.getType(), newTarget, ((FieldDereference) expression).getField());
        }

        return expression;
    }

    /**
     * Returns the assignment key corresponding to the column represented by {@param projectedColumn} in the {@param assignments}, if one exists.
     */
    public static Optional<String> find(Map<String, ColumnHandle> assignments, ProjectedColumnRepresentation projectedColumn)
    {
        HiveColumnHandle variableColumn = (HiveColumnHandle) assignments.get(projectedColumn.getVariable().getName());

        if (variableColumn == null) {
            return Optional.empty();
        }

        String baseColumnName = variableColumn.getBaseColumnName();

        List<Integer> variableColumnIndices = variableColumn.getHiveColumnProjectionInfo()
                .map(HiveColumnProjectionInfo::getDereferenceIndices)
                .orElse(ImmutableList.of());

        List<Integer> projectionIndices = ImmutableList.<Integer>builder()
                .addAll(variableColumnIndices)
                .addAll(projectedColumn.getDereferenceIndices())
                .build();

        for (Map.Entry<String, ColumnHandle> entry : assignments.entrySet()) {
            HiveColumnHandle column = (HiveColumnHandle) entry.getValue();
            if (column.getBaseColumnName().equals(baseColumnName) &&
                    column.getHiveColumnProjectionInfo()
                        .map(HiveColumnProjectionInfo::getDereferenceIndices)
                        .orElse(ImmutableList.of())
                        .equals(projectionIndices)) {
                return Optional.of(entry.getKey());
            }
        }

        return Optional.empty();
    }

    public static class ProjectedColumnRepresentation
    {
        private final Variable variable;
        private final List<Integer> dereferenceIndices;

        public ProjectedColumnRepresentation(Variable variable, List<Integer> dereferenceIndices)
        {
            this.variable = requireNonNull(variable, "variable is null");
            this.dereferenceIndices = ImmutableList.copyOf(requireNonNull(dereferenceIndices, "dereferenceIndices is null"));
        }

        public Variable getVariable()
        {
            return variable;
        }

        public List<Integer> getDereferenceIndices()
        {
            return dereferenceIndices;
        }

        public boolean isVariable()
        {
            return dereferenceIndices.isEmpty();
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }
            if ((obj == null) || (getClass() != obj.getClass())) {
                return false;
            }
            ProjectedColumnRepresentation that = (ProjectedColumnRepresentation) obj;
            return Objects.equals(variable, that.variable) &&
                    Objects.equals(dereferenceIndices, that.dereferenceIndices);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(variable, dereferenceIndices);
        }
    }
}
