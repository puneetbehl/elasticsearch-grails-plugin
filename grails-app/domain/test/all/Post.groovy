package test.all

/**
 * @author <a href='mailto:donbeave@gmail.com'>Alexey Zhokhov</a>
 */
class Post {

    String subject
    String body

    static searchable = {
        //all = [analyzer: 'repl_analyzer'] // _all is deprecated in ES 6
        subject analyzer: 'test_analyzer'
        body analyzer: 'test_analyzer'
    }

}
