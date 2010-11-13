/**
 * Copyright (C) 2009 hprange <hprange@gmail.com>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.wounit.rules;

import java.lang.reflect.Field;

import com.webobjects.eoaccess.EOAttribute;
import com.webobjects.eoaccess.EOEntity;
import com.webobjects.eoaccess.EOModelGroup;
import com.webobjects.eoaccess.EOUtilities;
import com.webobjects.eocontrol.EOClassDescription;
import com.webobjects.eocontrol.EOCustomObject;
import com.webobjects.eocontrol.EOEditingContext;
import com.webobjects.eocontrol.EOEnterpriseObject;
import com.webobjects.eocontrol.EOFetchSpecification;
import com.webobjects.eocontrol.EOGlobalID;
import com.webobjects.eocontrol.EOObjectStoreCoordinator;
import com.webobjects.eocontrol.EOQualifier;
import com.webobjects.eocontrol.EOTemporaryGlobalID;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSRange;

import er.extensions.eof.ERXQ;
import er.extensions.eof.ERXS;
import er.extensions.foundation.ERXArrayUtilities;

/**
 * <code>MockEditingContext</code> is a subclass of
 * {@link AbstractEditingContextRule} that provides means for fast in-memory
 * testing of <code>EOEnterpriseObject</code>s.
 * <p>
 * This class is useful for unit testing because it allows the creation of saved
 * objects that don't participate in the augmented transaction process. This
 * kind of feature is useful to validate the behavior of a unit in isolation.
 * 
 * <pre>
 * public class TestMyModel {
 *     &#064;Rule
 *     public MockEditingContext editingContext = new MockEditingContext(&quot;MyModel&quot;);
 * 
 *     &#064;Test
 *     public void testingMyModelLogic() throws Exception {
 * 	MyEntity instance = MyEntity.createMyEntity(editingContext);
 * 
 * 	AnotherEntity mockInstance = editingContext.createSavedObject(AnotherEntity.class);
 * 
 * 	// Do something with instance using the mockInstance...
 *     }
 * }
 * </pre>
 * 
 * @author <a href="mailto:hprange@gmail.com">Henrique Prange</a>
 * @since 1.0
 */
public class MockEditingContext extends AbstractEditingContextRule {
    private static final String ENTITY_NAME_KEY = "entityName";
    private static final long serialVersionUID = 6385728714740347428L;
    private int globalFakeId = 1;

    /**
     * Constructor only for test purposes.
     */
    MockEditingContext(EOObjectStoreCoordinator objectStore, String... modelNames) {
	super(objectStore, modelNames);
    }

    /**
     * Creates a <code>MockEditingContext</code> and loads all models with name
     * specified by parameter.
     * 
     * @param modelNames
     *            the name of all models required by unit tests.
     */
    public MockEditingContext(String... modelNames) {
	this(new MockObjectStoreCoordinator(), modelNames);
    }

    private EOGlobalID createPermanentGlobalFakeId(String entityName) {
	EOEntity entity = EOUtilities.entityNamed(this, entityName);

	NSArray<EOAttribute> primaryKeyAttributes = entity.primaryKeyAttributes();

	if (primaryKeyAttributes.count() != 1) {
	    throw new IllegalArgumentException(String.format("%s has a compound primary key and can't be used to create mock instances.", entityName));
	}

	String primaryKeyName = (primaryKeyAttributes.objectAtIndex(0)).name();

	NSDictionary<String, Integer> primaryKeyDictionary = new NSDictionary<String, Integer>(globalFakeId, primaryKeyName);

	globalFakeId++;

	return entity.globalIDForRow(primaryKeyDictionary);
    }

    /**
     * Create an instance of the specified class and insert into the mock
     * editing context.
     * 
     * @param <T>
     *            the static type of the instance that should be instantiated
     * @param clazz
     *            the class of the entity that should be instantiated
     * @return an instance of the given class
     */
    @SuppressWarnings("unchecked")
    public <T extends EOEnterpriseObject> T createSavedObject(Class<T> clazz) {
	if (clazz == null) {
	    throw new IllegalArgumentException("Cannot create an instance for a null class.");
	}

	String entityName = clazz.getSimpleName();

	if (EOModelGroup.defaultGroup().entityNamed(entityName) != null) {
	    return (T) createSavedObject(entityName);
	}

	try {
	    Field field = clazz.getField("ENTITY_NAME");

	    entityName = (String) field.get(null);
	} catch (Exception exception) {
	    throw new IllegalArgumentException("Cannot create an instance based on the provided class. Please, provide an entity name instead.", exception);
	}

	T instance = (T) createSavedObject(entityName);

	return instance;
    }

    /**
     * Create an instance of the specified entity named and insert into the mock
     * editing context.
     * 
     * @param <T>
     *            the static type of the enterprise object returned by this
     *            method
     * @param entityName
     *            the name of the entity that should be instantiated
     * @return an instance of the given entity named
     */
    public <T extends EOEnterpriseObject> T createSavedObject(String entityName) {
	if (entityName == null) {
	    throw new IllegalArgumentException("Cannot create an instance for a null entity name.");
	}

	EOClassDescription classDescription = EOClassDescription.classDescriptionForEntityName(entityName);

	if (classDescription == null) {
	    throw new IllegalArgumentException(String.format("Could not find EOClassDescription for entity name '%s'.", entityName));
	}

	@SuppressWarnings("unchecked")
	T eo = (T) classDescription.createInstanceWithEditingContext(this, null);

	insertSavedObject(eo);

	return eo;
    }

    /**
     * Insert the instance specified by parameter into the mock editing context.
     * 
     * @param eo
     *            The <code>EOEnterpriseObject</code> that should inserted
     */
    public void insertSavedObject(EOEnterpriseObject eo) {
	EOGlobalID globalId = createPermanentGlobalFakeId(eo.entityName());

	recordObject(eo, globalId);

	((EOCustomObject) eo).__setGlobalID(new EOTemporaryGlobalID());

	eo.awakeFromInsertion(this);

	((EOCustomObject) eo).__setGlobalID(globalId);
    }

    /**
     * Reimplementation of the original method to return only the objects
     * registered in memory.
     * 
     * @see er.extensions.eof.ERXEC#objectsWithFetchSpecification(com.webobjects.eocontrol.EOFetchSpecification,
     *      com.webobjects.eocontrol.EOEditingContext)
     */
    @Override
    public NSArray<EOEnterpriseObject> objectsWithFetchSpecification(EOFetchSpecification fetchSpecification, EOEditingContext editingContext) {
	@SuppressWarnings("unchecked")
	NSArray<EOEnterpriseObject> availableObjects = ERXArrayUtilities.arrayMinusArray(registeredObjects(), deletedObjects());

	String entityName = fetchSpecification.entityName();

	EOQualifier qualifier = ERXQ.is(ENTITY_NAME_KEY, entityName);

	if (fetchSpecification.isDeep()) {
	    NSArray<EOEntity> subEntities = EOUtilities.entityNamed(editingContext, entityName).subEntities();

	    for (EOEntity entity : subEntities) {
		qualifier = ERXQ.or(qualifier, ERXQ.is(ENTITY_NAME_KEY, entity.name()));
	    }
	}

	qualifier = ERXQ.and(qualifier, fetchSpecification.qualifier());

	availableObjects = ERXQ.filtered(availableObjects, qualifier);

	availableObjects = ERXS.sorted(availableObjects, fetchSpecification.sortOrderings());

	if (fetchSpecification.fetchLimit() > 0) {
	    return availableObjects.subarrayWithRange(new NSRange(0, fetchSpecification.fetchLimit()));
	}

	return availableObjects;
    }

    /**
     * Reimplementation of the original method to set a fake
     * <code>EOGlobalID</code> for inserted objects being saved for the first
     * time.
     * 
     * @see er.extensions.eof.ERXEC#saveChanges()
     */
    @Override
    public void saveChanges() {
	@SuppressWarnings("unchecked")
	NSArray<EOEnterpriseObject> insertedObjects = ERXArrayUtilities.arrayMinusArray(insertedObjects(), deletedObjects());

	super.saveChanges();

	for (EOEnterpriseObject insertedObject : insertedObjects) {
	    forgetObject(insertedObject);

	    EOGlobalID globalId = createPermanentGlobalFakeId(insertedObject.entityName());

	    recordObject(insertedObject, globalId);
	}
    }
}
