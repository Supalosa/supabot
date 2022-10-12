# Supabot

Terrible Starcraft 2 bot under development.

## Authors
* Supalosa (https://github.com/supalosa)

## Setup
It's recommended to look at https://github.com/ocraft/ocraft-s2client before attempting to set up this bot.

This project is built with Gradle. To set up development on this bot, import this project as a Gradle project to IntelliJ.
It will pull in all dependencies that are required.

## Execution
To run the bot against the Blizzard AI, simply run the main method in the `src/main/java/com/supalosa/Main.java` class.

To build a version that's suitable for the https://aiarena.net/ ladder, you must build the shadowJar using gradle:

    ./gradlew shadowJar

The output will appear in build/libs/supabot.jar.
Zip that file, along with a LadderBots.json file like the below:

```
{
  "Bots": {
    "supabot": {
      "Race": "Terran",
      "Type": "Java",
      "RootPath": "./",
      "FileName": "supabot.jar",
      "Debug": true,
      "SurrenderPhrase": "pineapple"
    }
  }
}
```
Into a file called `supabot.zip`. This can be uploaded or played using the LadderManager.
