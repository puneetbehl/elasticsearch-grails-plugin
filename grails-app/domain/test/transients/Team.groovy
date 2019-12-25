package test.transients

class Team {

    String name
    String strip

    static hasMany = [players: Player, fans: Fan]

    static searchable = {
        only = ["players", "name"]
        players component: true
        fans reference: true
    }

    static transients = ["players", "fans"]

    def getPlayers() {
        return Player.findAll()
    }

    def getFans() {
        return Fan.findAll()
    }

}
