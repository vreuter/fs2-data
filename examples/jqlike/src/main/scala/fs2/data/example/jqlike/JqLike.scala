package fs2.data.example.jqlike

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.Stream
import fs2.data.json.jq.{Jq, JqParser}
import fs2.data.json.{jq, render, tokens}
import fs2.io.file.{Files, Path}

import java.nio.file.{Path => JPath}

object JqLike extends CommandIOApp(name = "fs2-jq", header = "A streaming implementation of a jq-like tool") {

  val query: Opts[Option[String]] =
    Opts
      .option[String](long = "query", short = "q", help = "The query to execute on the input (default to '.')")
      .orNone

  val input: Opts[Either[String, Path]] =
    Opts
      .option[String](long = "input", short = "i", help = "The input json string")
      .map(_.asLeft)
      .orElse(
        Opts.option[JPath](long = "file", short = "f", help = "The input json file").map(Path.fromNioPath(_).asRight))

  val output: Opts[Option[Path]] = Opts
    .option[JPath](long = "output", short = "o", help = "The output file (outputs to stdout if not provided)")
    .map(Path.fromNioPath(_))
    .orNone

  override def main: Opts[IO[ExitCode]] =
    (query, input, output)
      .mapN { (query, input, output) =>
        val queryCompiler = jq.Compiler[IO]
        for {
          // first parse the provided query
          query <- query.fold(IO.pure(Jq.Identity: Jq))(JqParser.parse[IO](_))
          // then compile it
          compiled <- queryCompiler.compile(query)
          (time, _) <- input
            // then read either from the string input or from the file input
            .fold(Stream.emit(_), Files[IO].readUtf8(_))
            // parse the input as json
            .through(tokens)
            // execute the compiled query on the input
            .through(compiled)
            // render the query result
            .through(render.pretty())
            // encode the result
            .through(fs2.text.utf8.encode[IO])
            // and save it to the output
            .through(output.fold(fs2.io.stdout[IO])(Files[IO].writeAll(_)))
            // finally run all the things
            .compile
            .drain
            .timed
          _ <- output.fold(IO.println(""))(p => IO.println(s"Result written to $p"))
          _ <- IO.println(s"Processed in ${time.toMillis}ms")
        } yield ExitCode.Success
      }
      .map(_.handleErrorWith(t => IO.println(t.getMessage()).as(ExitCode.Error)))

}
