/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.cassandra.repository.support;

import java.io.Serializable;
import java.lang.reflect.Method;

import org.springframework.data.cassandra.core.ReactiveCassandraOperations;
import org.springframework.data.cassandra.mapping.CassandraMappingContext;
import org.springframework.data.cassandra.mapping.CassandraPersistentEntity;
import org.springframework.data.cassandra.repository.query.CassandraEntityInformation;
import org.springframework.data.cassandra.repository.query.ReactiveCassandraQueryMethod;
import org.springframework.data.cassandra.repository.query.ReactivePartTreeCassandraQuery;
import org.springframework.data.cassandra.repository.query.ReactiveStringBasedCassandraQuery;
import org.springframework.data.mapping.model.MappingException;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.core.NamedQueries;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.ReactiveRepositoryFactorySupport;
import org.springframework.data.repository.query.EvaluationContextProvider;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryLookupStrategy.Key;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.Assert;

/**
 * Factory to create {@link org.springframework.data.cassandra.repository.ReactiveCassandraRepository} instances.
 *
 * @author Mark Paluch
 * @since 2.0
 */
public class ReactiveCassandraRepositoryFactory extends ReactiveRepositoryFactorySupport {

	private static final SpelExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

	private final ReactiveCassandraOperations operations;
	private final CassandraMappingContext mappingContext;

	/**
	 * Create a new {@link ReactiveCassandraRepositoryFactory} with the given {@link ReactiveCassandraOperations}.
	 *
	 * @param cassandraOperations must not be {@literal null}.
	 */
	public ReactiveCassandraRepositoryFactory(ReactiveCassandraOperations cassandraOperations) {

		Assert.notNull(cassandraOperations, "ReactiveCassandraOperations must not be null");

		this.operations = cassandraOperations;
		this.mappingContext = cassandraOperations.getConverter().getMappingContext();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.core.support.RepositoryFactorySupport#getRepositoryBaseClass(org.springframework.data.repository.core.RepositoryMetadata)
	 */
	@Override
	protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
		return SimpleReactiveCassandraRepository.class;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.core.support.RepositoryFactorySupport#getTargetRepository(org.springframework.data.repository.core.RepositoryInformation)
	 */
	@Override
	protected Object getTargetRepository(RepositoryInformation information) {

		CassandraEntityInformation<?, Serializable> entityInformation = getEntityInformation(information.getDomainType());

		return getTargetRepositoryViaReflection(information, entityInformation, operations);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.core.support.RepositoryFactorySupport#getQueryLookupStrategy(org.springframework.data.repository.query.QueryLookupStrategy.Key, org.springframework.data.repository.query.EvaluationContextProvider)
	 */
	@Override
	protected QueryLookupStrategy getQueryLookupStrategy(Key key, EvaluationContextProvider evaluationContextProvider) {
		return new CassandraQueryLookupStrategy(operations, evaluationContextProvider, mappingContext);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.core.support.RepositoryFactorySupport#getEntityInformation(java.lang.Class)
	 */
	@SuppressWarnings("unchecked")
	public <T, ID extends Serializable> CassandraEntityInformation<T, ID> getEntityInformation(Class<T> domainClass) {
		CassandraPersistentEntity<?> entity = mappingContext.getPersistentEntity(domainClass);

		if (entity == null) {
			throw new MappingException(
					String.format("Could not lookup mapping metadata for domain class %s!", domainClass.getName()));
		}

		return new MappingCassandraEntityInformation<>((CassandraPersistentEntity<T>) entity, operations.getConverter());
	}

	/**
	 * {@link QueryLookupStrategy} to create
	 * {@link org.springframework.data.cassandra.repository.query.PartTreeCassandraQuery} instances.
	 *
	 * @author Mark Paluch
	 */
	private static class CassandraQueryLookupStrategy implements QueryLookupStrategy {

		private final EvaluationContextProvider evaluationContextProvider;
		private final ReactiveCassandraOperations operations;
		private final CassandraMappingContext mappingContext;

		CassandraQueryLookupStrategy(ReactiveCassandraOperations operations,
				EvaluationContextProvider evaluationContextProvider, CassandraMappingContext mappingContext) {

			this.evaluationContextProvider = evaluationContextProvider;
			this.operations = operations;
			this.mappingContext = mappingContext;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.repository.query.QueryLookupStrategy#resolveQuery(java.lang.reflect.Method, org.springframework.data.repository.core.RepositoryMetadata, org.springframework.data.projection.ProjectionFactory, org.springframework.data.repository.core.NamedQueries)
		 */
		@Override
		public RepositoryQuery resolveQuery(Method method, RepositoryMetadata metadata, ProjectionFactory factory,
				NamedQueries namedQueries) {

			ReactiveCassandraQueryMethod queryMethod = new ReactiveCassandraQueryMethod(method, metadata, factory,
					mappingContext);

			String namedQueryName = queryMethod.getNamedQueryName();

			if (namedQueries.hasQuery(namedQueryName)) {
				String namedQuery = namedQueries.getQuery(namedQueryName);

				return new ReactiveStringBasedCassandraQuery(namedQuery, queryMethod, operations, EXPRESSION_PARSER,
						evaluationContextProvider);
			} else if (queryMethod.hasAnnotatedQuery()) {
				return new ReactiveStringBasedCassandraQuery(queryMethod, operations, EXPRESSION_PARSER,
						evaluationContextProvider);
			} else {
				return new ReactivePartTreeCassandraQuery(queryMethod, operations);
			}
		}
	}
}