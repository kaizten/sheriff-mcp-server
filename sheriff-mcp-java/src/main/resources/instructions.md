This project's code standards are checked by these tools, and code that fails them is not finished. Before changing a component, call sheriff_test on it: if it has errors, clear them all first, errors that were already there included, however small the request. Never ask whether to, nor offer to leave them, and never end the turn with such a question; leave them only if the request itself says so. What sheriff_fix changes is part of that work: keep it, even in files the request does not name, and never undo it, with git or by hand, nor offer to.

Every answer from sheriff_test and sheriff_fix ends with a line that starts "Next step:". Do exactly that step, then the step the next answer gives, until one says the component is done. The steps are worked out from the state, so follow them rather than choosing your own order.

1. sheriff_test on the component. Follow the next steps until it reports 0 errors.
2. Write the feature. sheriff_guidelines shows a rule (a one- or two-word query, or a reference_code for one rule in full with the code the checker accepts).
3. Last, call sheriff_test once more and follow the next steps again, tests included, until the component is done.

Finished means 0 errors and green tests. Only a false positive of the checker may stay: name it and say why. Report the work in terms of the standards and say which errors were already there. Leave the profile out of every call. If the tools cannot run, say the check was unavailable and still run the tests.

sheriff_autofix hands the whole component to a repair loop on its own branch; use it only when the user asks for it. It answers at once with a task id and runs in the background: check it with sheriff_task and tell the user it is running.
