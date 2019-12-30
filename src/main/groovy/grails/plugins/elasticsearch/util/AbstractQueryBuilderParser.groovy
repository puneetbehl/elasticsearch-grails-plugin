package grails.plugins.elasticsearch.util

import groovy.transform.CompileStatic
import org.elasticsearch.common.ParsingException
import org.elasticsearch.common.xcontent.NamedObjectNotFoundException
import org.elasticsearch.common.xcontent.XContentLocation
import org.elasticsearch.common.xcontent.XContentParser
import org.elasticsearch.index.query.QueryBuilder

@CompileStatic
class AbstractQueryBuilderParser {

    /**
     * Parses a query excluding the query element that wraps it
     **/
    static QueryBuilder parseInnerQueryBuilder(XContentParser parser) throws IOException {
        if (parser.currentToken() != XContentParser.Token.START_OBJECT) {
            if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                throw new ParsingException(parser.tokenLocation, '[_na] query malformed, must start with start_object')
            }
        }
        if (parser.nextToken() == XContentParser.Token.END_OBJECT) {
            // we encountered '{}' for a query clause, it used to be supported, deprecated in 5.0 and removed in 6.0
            throw new IllegalArgumentException("query malformed, empty clause found at [${parser.tokenLocation}]")
        }

        if (parser.currentToken() != XContentParser.Token.FIELD_NAME) {
            throw new ParsingException(parser.tokenLocation, '[_na] query malformed, no field after start_object')
        }

        String queryName = parser.currentName()

        // move to the next START_OBJECT
        if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
            throw new ParsingException(parser.tokenLocation, "[$queryName] query malformed, no start_object after query name")
        }

        QueryBuilder result
        try {
            result = parser.namedObject(QueryBuilder, queryName, parser)
        } catch (NamedObjectNotFoundException e) {
            throw new ParsingException(new XContentLocation(e.lineNumber, e.columnNumber), e.toString())
        }

        //end_object of the specific query (e.g. match, multi_match etc.) element
        if (parser.currentToken() != XContentParser.Token.END_OBJECT) {
            throw new ParsingException(parser.tokenLocation, "[$queryName] malformed query, expected [END_OBJECT] but found [${parser.currentToken()}]")
        }

        //end_object of the query object
        if (parser.nextToken() != XContentParser.Token.END_OBJECT) {
            throw new ParsingException(parser.tokenLocation, "[$queryName] malformed query, expected [END_OBJECT] but found [${parser.currentToken()}]")
        }

        result
    }

}
