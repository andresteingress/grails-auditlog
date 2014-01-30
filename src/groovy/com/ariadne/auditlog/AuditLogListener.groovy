package com.ariadne.auditlog

import com.ariadne.domain.AuditLogEvent
import groovy.util.logging.Commons
import org.codehaus.groovy.grails.commons.GrailsDomainClass
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.engine.event.*
import org.springframework.context.ApplicationEvent
import org.springframework.web.context.request.RequestContextHolder

import static com.ariadne.auditlog.AuditLogListenerUtil.*

/**
 * Grails interceptor for logging saves, updates, deletes and acting on
 * individual properties changes and delegating calls back to the Domain Class
 */
@Commons
class AuditLogListener extends AbstractPersistenceEventListener {
    def grailsApplication

    /**
     * The verbose flag flips on and off column by column change logging in
     * insert and delete events. If this is true then all columns are logged
     * each as an individual event.
     *
     * If verbose is set to 'true' then you get a log event on
     * each individually changed column/field sent to the database
     * with a record of the old value and the new value.
     *
     * auditLog.verbose = true
     */
    Boolean verbose = true
    Boolean logIds = false
    Boolean transactional = false
    Integer truncateLength
    String sessionAttribute
    String actorKey
    String propertyMask
    Closure actorClosure

    // Global list of attribute changes to ignore, defaults to ['version', 'lastUpdated']
    List<String> defaultIncludeList
    List<String> defaultIgnoreList

    List<String> defaultMaskList

    Map<String, String> replacementPatterns

    AuditLogListener(Datastore datastore) {
        super(datastore)
    }

    @Override
    protected void onPersistenceEvent(AbstractPersistenceEvent event) {
        if (isAuditableEntity(event.entityObject, getEventName(event))) {
            log.trace "Audit logging: ${event.eventType.name()} for ${event.entityObject.class.name}"

            switch(event.eventType) {
                case EventType.PostInsert:
                    onPostInsert(event as PostInsertEvent)
                    break
                case EventType.PreUpdate:
                    onPreUpdate(event as PreUpdateEvent)
                    break
                case EventType.PreDelete:
                    onPreDelete(event as PreDeleteEvent)
                    break
            }
        }
    }

    @Override
    boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return eventType.isAssignableFrom(PostInsertEvent) ||
                eventType.isAssignableFrom(PreUpdateEvent) ||
                eventType.isAssignableFrom(PreDeleteEvent)
    }

    void setActorClosure(Closure closure) {
        closure.delegate = this
        closure.properties['log'] = log
        actorClosure = closure
    }

    String getActor() {
        def actor = null
        if (actorClosure) {
            def attr = RequestContextHolder.getRequestAttributes()
            def session = attr?.session
            if (attr && session) {
                try {
                    actor = actorClosure.call(attr, session)
                }
                catch(ex) {
                    log.error "The auditLog.actorClosure threw this exception", ex
                    log.error "The auditLog.actorClosure will be disabled now."
                    actorClosure = null
                }
            }
            // If we couldn't find an actor, use the configured default or just 'system'
            if (!actor) {
                actor = grailsApplication.config.auditLog.defaultActor ?: 'system'
            }
        }
        return actor?.toString()
    }

    String getUri() {
        def attr = RequestContextHolder?.getRequestAttributes()
        return (attr?.currentRequest?.uri?.toString()) ?: null
    }

    /**
     * We allow users to specify static auditable = [handlersOnly: true]
     * if they don't want us to log events for them and instead have their own plan.
     */
    boolean callHandlersOnly(domain) {
        // Allow global configuration of handlers only
        if (grailsApplication.config.auditLog.handlersOnly) {
            return true
        }

        Map auditableMap = getAuditableMap(domain)
        if (auditableMap?.containsKey('handlersOnly')) {
            return (auditableMap['handlersOnly']) ? true : false
        }
        return false
    }

    /**
     * The default properties to mask list is:  ['password']
     * if you want to provide your own mask list, specify in the DomainClass:
     *
     *   static auditable = [mask:['password','myField']]
     *
     * If you really want to log password property change values
     * specify an empty mask list:
     *
     *   static auditable = [mask:[]]
     *
     * Or globally:
     *
     *   auditLog.defaultMask = ['password']
     *
     */
    List maskList(domain) {
        def mask = defaultMaskList

        Map auditableMap = getAuditableMap(domain)
        if (auditableMap?.containsKey('mask')) {
            log.debug "Found a mask list one this entity ${domain.class.name}"
            def list = domain.auditable['mask']
            if (list instanceof List) {
                mask = list
            }
        }

        return mask
    }

    /**
     * We must use the preDelete event if we want to capture
     * what the old object was like.
     */
    protected void onPreDelete(PreDeleteEvent event) {
        def domain = event.entityObject
        try {
            def entity = getDomainClass(domain)

            def map = makeMap(filterProperties(entity.persistentProperties*.name as Set, domain), domain)
            if (!callHandlersOnly(domain)) {
                logChanges(domain, null, map, getEntityId(domain), getEventName(event), entity.name)
            }

            executeHandler(domain, 'onDelete', map, null)
        }
        catch (e) {
            log.error "Audit plugin unable to process delete event for ${domain.class.name}", e
        }
    }

    /**
     * I'm using the post insert event here instead of the pre-insert
     * event so that I can log the id of the new entity after it
     * is saved. That does mean the the insert event handlers
     * can't work the way we want... but really it's the onChange
     * event handler that does the magic for us.
     */
    protected void onPostInsert(PostInsertEvent event) {
        def domain = event.entityObject
        try {
            def entity = getDomainClass(domain)

            def map = makeMap(filterProperties(entity.persistentProperties*.name as Set, domain), domain)
            if (!callHandlersOnly(domain)) {
                logChanges(domain, map, null, getEntityId(domain), getEventName(event), entity.name)
            }

            executeHandler(domain, 'onSave', null, map)
        }
        catch (e) {
            log.error "Audit plugin unable to process insert event for ${domain.class.name}", e
        }
    }

    /**
     * Now we get fancy. Here we want to log changes...
     * specifically we want to know what property changed,
     * when it changed. And what the differences were.
     *
     * This works better from the onPreUpdate event handler
     * but for some reason it won't execute right for me.
     * Instead I'm doing a rather complex mapping to build
     * a pair of state HashMaps that represent the old and
     * new states of the object.
     *
     * The old and new states are passed to the object's
     * 'onChange' event handler. So that a user can work
     * with both sets of values.
     *
     * Needs complex type testing BTW.
     */
    protected void onPreUpdate(PreUpdateEvent event) {
        def domain = event.entityObject
        try {
            def entity = getDomainClass(domain)

            // Get all the dirty properties
            Set<String> dirtyProperties = getDirtyPropertyNames(domain, entity)

            dirtyProperties = filterProperties(dirtyProperties, domain)

            if (dirtyProperties) {
                // Get the prior values for everything that is dirty
                Map oldMap = dirtyProperties.collectEntries { String property ->
                    [property, getPersistentValue(domain, property, entity)]
                }

                // Get the current values for everything that is dirty
                Map newMap = makeMap(dirtyProperties, domain)

                // Allow user to override whether you do auditing for them
                if (!callHandlersOnly(domain)) {
                    logChanges(domain, newMap, oldMap, getEntityId(domain), getEventName(event), entity.name)
                }

                executeHandler(domain, 'onChange', oldMap, newMap)
            }
        }
        catch (e) {
            log.error "Audit plugin unable to process update event for ${domain.class.name}", e
        }
    }

    Set<String> filterProperties(Set<String> properties, domain) {
        // remove all ignored properties
        properties.removeAll(AuditClosureLookup.ignoreList(domain.class, defaultIgnoreList))

        // intersect with included properties
        properties.intersect(AuditClosureLookup.includeList(domain.class, defaultIncludeList))
    }

    /**
     * Get the persistent value for the given domain.property. This method includes
     * some special case handling for hasMany properties, which don't follow normal rules.
     *
     * TODO - Need a way to load the old value generically for a collection
     */
    protected getPersistentValue(domain, String property, GrailsDomainClass entity) {
        if (entity.isOneToMany(property)) {
            "N/A"
        }
        else {
            domain.getPersistentValue(property)
        }
    }

    /**
     * Return dirty property names for the given domain class. This method includes some
     * special case logic for hasMany properties, which don't follow normal isDirty rules.
     */
    protected Set<String> getDirtyPropertyNames(domain, GrailsDomainClass entity) {
        Set<String> dirtyProperties = domain.dirtyPropertyNames ?: []

        // In some cases, collections aren't listed as being dirty in the dirty property names.
        // We need to check them individually.
        entity.associationMap.each { String associationName, value ->
            if (entity.isOneToMany(associationName)) {
                def collection = domain."${associationName}"
                if (collection?.respondsTo('isDirty') && collection?.isDirty()) {
                    dirtyProperties << associationName
                }
            }
        }

        dirtyProperties
    }

    private makeMap(Set<String> propertyNames, domain) {
        propertyNames.collectEntries { [it, domain."${it}"] }
    }

    private String getEventName(AbstractPersistenceEvent event) {
        switch(event.eventType) {
            case EventType.PostInsert:
                return "INSERT"
            case EventType.PreUpdate:
                return "UPDATE"
            case EventType.PreDelete:
                return "DELETE"
            default:
                throw new IllegalStateException("Unknown event type: ${event.eventType}")
        }
    }

    /**
     * Leans heavily on the "toString()" of a property
     * ... this feels crufty... should be tighter...
     */
    def logChanges(domain, Map newMap, Map oldMap, persistedObjectId, eventName, className) {
        if (newMap && oldMap) {
            log.trace "There are new and old values to log"
            newMap.each { String key, val ->
                if (val != oldMap[key]) {
                    def audit = new AuditLogEvent(
                            actor: getActor(),
                            uri: getUri(),
                            className: className,
                            eventName: eventName,
                            persistedObjectId: persistedObjectId?.toString(),
                            propertyName: key,
                            oldValue: conditionallyMaskAndTruncate(domain, key, oldMap[key]),
                            newValue: conditionallyMaskAndTruncate(domain, key, newMap[key]))
                    saveAuditLog(audit)
                }
            }
        }
        else if (newMap && verbose) {
            log.trace "there are new values and logging is verbose ... "
            newMap.each { String key, val ->
                def audit = new AuditLogEvent(
                        actor: getActor(),
                        uri: getUri(),
                        className: className,
                        eventName: eventName,
                        persistedObjectId: persistedObjectId?.toString(),
                        propertyName: key,
                        oldValue: null,
                        newValue: conditionallyMaskAndTruncate(domain, key, val))
                saveAuditLog(audit)
            }
        }
        else if (oldMap && verbose) {
            log.trace "there is only an old map of values available and logging is set to verbose... "
            oldMap.each { String key, val ->
                def audit = new AuditLogEvent(
                        actor: getActor(),
                        uri: getUri(),
                        className: className,
                        eventName: eventName,
                        persistedObjectId: persistedObjectId?.toString(),
                        propertyName: key,
                        oldValue: conditionallyMaskAndTruncate(domain, key, val),
                        newValue: null)
                saveAuditLog(audit)
            }
        }
        else {
            log.trace "creating a basic audit logging event object."
            def audit = new AuditLogEvent(
                    actor: getActor(),
                    uri: getUri(),
                    className: className,
                    eventName: eventName,
                    persistedObjectId: persistedObjectId?.toString())
            saveAuditLog(audit)
        }
    }

    /**
     * @param domain the auditable domain object
     * @param key property name
     * @param value the value of the property
     * @return
     */
    String conditionallyMaskAndTruncate(domain, String key, value){
        if (maskList(domain)?.contains(key)){
            log.trace("Masking property ${key} with ${propertyMask}")
            propertyMask
        }
        else if (truncateLength) {
            truncate(value, truncateLength)
        }
        else {
            truncate(value, Integer.MAX_VALUE)
        }
    }

    String truncate(value, int max) {
        if (value == null) {
            return null
        }

        log.trace "trimming object's string representation based on ${max} characters."

        // GPAUDITLOGGING-43
        def str = null
        if (logIds) {
            if (value instanceof Collection || value instanceof Map) {
                value.each {
                    str = appendWithId(it, str)
                }
            }
            else {
                str = appendWithId(value, str)
            }
        }
        else {
            str = "$value".trim() // GPAUDITLOGGING-40
        }

        str = replaceByReplacementPatterns(str)
        (str?.length() > max) ? str?.substring(0, max) : str
    }

    private String appendWithId(obj, str) {
        // If this is a domain object, use the standard entity id which
        // allows the domain class to determine what property to use
        def objId = null
        if (obj && grailsApplication.isDomainClass(obj.class)) {
            objId = getEntityId(obj)
        }
        else if (obj?.respondsTo("getId")) {
            objId = obj.id
        }

        // If we have an object id, use it otherwise just fallback to toString()
        if (objId) {
            str ? "$str, [id:${objId}]$obj" : "[id:${objId}]$obj"
        }
        else {
            str ? "$str,$obj" : "$obj"
        }
    }

    private String replaceByReplacementPatterns(String str) {
        if (str == null) {
            return null
        }
        replacementPatterns?.each { String from, String to ->
            str = str.replace(from, to)
        }
        return str
    }

    /**
     * This calls the handlers based on what was passed in to it.
     */
    def executeHandler(domain, handler, oldState, newState) {
        log.trace "calling execute handler ... "

        if (domain.metaClass.hasProperty(domain, handler)) {
            log.trace "entity was auditable and had a handler property ${handler}"
            if (oldState && newState) {
                log.trace "there was both an old state and a new state"
                if (domain."${handler}".maximumNumberOfParameters == 2) {
                    log.trace "there are two parameters to the handler so I'm sending old and new value maps"
                    domain."${handler}"(oldState, newState)
                }
                else {
                    log.trace "only one parameter on the closure I'm sending oldMap and newMap as part of a Map parameter"
                    domain."${handler}"([oldMap: oldState, newMap: newState])
                }
            }
            else if (oldState) {
                log.trace "sending old state into ${handler}"
                domain."${handler}"(oldState)
            }
            else if (newState) {
                log.trace "sending new state into ${handler}"
                domain."${handler}"(newState)
            }
        }
        log.trace "... execute handler is finished."
    }

    /**
     * Save the audit log in a new session and optionally, in a transaction
     *
     * It has also been written as a closure for your sake so that you may over-ride the
     * save closure with your own code (should your particular database not work with this code)
     * you may over-ride the definition of this closure using
     *
     * To debug in Config.groovy set:
     *    log4j.debug 'org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener'
     * or
     *    log4j.trace 'org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener'
     *
     * SEE: GRAILSPLUGINS-391
     */
    def saveAuditLog = { AuditLogEvent audit ->
        audit.with {
            dateCreated = lastUpdated = new Date()
        }
        log.info audit
        try {
            AuditLogEvent.withNewSession {
                if (transactional) {
                    AuditLogEvent.withTransaction {
                        audit.merge(flush: true, failOnError: true)
                    }
                }
                else {
                    audit.merge(flush: true, failOnError: true)
                }
            }
        }
        catch (e) {
            log.error "Failed to create AuditLogEvent for ${audit}", e
        }
    }
}
