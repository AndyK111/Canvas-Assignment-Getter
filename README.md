# Canvas Assignment Getter
This tool has a variety of modes that it uses to grab assignments from a students canvas accounts and display them in the terminal with a nice little UI. It is useful because I will no longer need to open a web browser to see my assignments that are due soon!s
## Functionality Gif
![[demo gif]](demo.gif)
## Setup Instructions
### Requirements
    - Java25
    - Gradle
    - Canvas API Token
### Steps
1. Compile the project with something like `gradle build` (For these steps im using `gradle fatJar`)
2. Run the program using `java -jar build/libs/canvas-assignment-cli-all.jar` (add `--help` for usage tips)
3. The program should display assignments from canvas based on mode and passed arguments.

## Example Commands
`java -jar build/libs/canvas-assignment-cli-all.jar quantity --before 2026-03-20 --quantity 10 --show-progress --group-by class`

There should be a titled table of assignments for each class, and each table should have 4 columns: Date, Time, Assignment, Status.
Note that in this mode it will return the most recent 10 assignments that are due before 2026-03-20

`java -jar build/libs/canvas-assignment-cli-all.jar --show-grades`

Produces the same table as the first example, but the after and before dates are automatically set to the *last* monday and the *next* sunday (respectively). Note that `--show-grades` is passed here so there is an additional column appended to the table that shows the current grade. This grade column conforms to the *type* of grade on the assignment. If its a % it shows a %, if its P/F it shows pass or fail, etc etc...

## API Endpoints Used
`get /api/v1/courses` : Active courses are fetched.
`get /api/v1/courses/:course_id/assignments` : From those active courses, we fetch assignments (which are turned into assignment class objects).

## Reflection
In a recent assignment I had my first experience with codex. I think it's fair to say that these tools are gonna stick around so I should dedicate some time to actually getting to know how to use them. Since the AI policy in this class is very lineant and very unambiguous, I decided to use this assignment as a "playground" for codex. The first thing I did was tell codex the requirements based on what was listed on the canvas page for the assignment, then I just told it to give me a weekly view in the CLI of assignments. (this was not the final product, I just wanted to see if it could do it *at all*, (and it did...)) 

After I had a very very basic working prototype, I noticed a whole lot of just really weird design decisions and a style that isn't very aligned with how I want code to look, so I had it do some style based refactoring and took a look at the result, and it was okay. Clearly it's not enough to just say "okay codex make this thing," so the second interation I got a bit more specific, specifying all arguments, what I want them to do, and the different modes, and which arguments they require. After that I specified what I wanted the project to look like architecturally. I told it what classes I wanted, and what functionality I wanted them to have, I'm very particular with my division of responsibility and encapsulation between classes, the first implementation from codex did not respect that at all. After giving it all of these specifications, it got to work doing a massive refactor, which (more-or-less) got the program in it's current state.

I will probably continue to work on this because it is genuinely useful to me, some thing's I want to add are:
- Add assignment "tags" that will tag different types of assignments
- Assigning a local directory to an assignment (if it relates to coding)
- If an assignment has a directory assigned, it will automatically search for a .git and tag it as a "coding" assignment
- Be able to submit assignments from the CLI, this will differ based on the tag, if its tagged with "coding", it will submit the repo link
- Be able to assign a local *file* to an assignment, based on file extension, tag may differ. (these will also be able to be turned in)