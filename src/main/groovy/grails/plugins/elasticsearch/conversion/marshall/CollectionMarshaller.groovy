package grails.plugins.elasticsearch.conversion.marshall

class CollectionMarshaller extends DefaultMarshaller {
    protected doMarshall(collection) {
        try {
            def marshallResult = collection.asList().collect {
                marshallingContext.delegateMarshalling(it)
            }
            return marshallResult
        } catch (NullPointerException npe) {
            return nullValue()
        }
    }

    protected nullValue() {
        return []
    }
}
